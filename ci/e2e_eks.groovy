//
// Jenkins pipeline for EKS e2e job.
//
// This script is written in declarative syntax. Refer to
// https://jenkins.io/doc/book/pipeline/syntax/ for more details.
//
// Note that parameters of the job is configured in this script.
//

import groovy.transform.Field

@Field
def podYAML = '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: main
    image: gcr.io/k8s-testimages/kubekins-e2e:v20200311-1e25827-master
    command:
    - runner.sh
    - sleep
    - 1d
    # we need privileged mode in order to do docker in docker
    securityContext:
      privileged: true
    env:
    - name: DOCKER_IN_DOCKER_ENABLED
      value: "true"
    resources:
      requests:
        memory: "4000Mi"
        cpu: 2000m
    volumeMounts:
    # dind expects /var/lib/docker to be volume
    - name: docker-root
      mountPath: /var/lib/docker
  volumes:
  - name: docker-root
    emptyDir: {}
'''

pipeline {
    agent {
        kubernetes {
            yaml podYAML
            defaultContainer "main"
            customWorkspace "/home/jenkins/agent/workspace/go/src/github.com/pingcap/tidb-operator"
        }
    }

    options {
        timeout(time: 3, unit: 'HOURS') 
    }

    parameters {
        string(name: 'GIT_URL', defaultValue: 'git@github.com:pingcap/tidb-operator.git', description: 'git repo url')
        string(name: 'GIT_REF', defaultValue: 'master', description: 'git ref spec to checkout, e.g. master, release-1.1')
        string(name: 'PR_ID', defaultValue: '', description: 'pull request ID, this will override GIT_REF if set, e.g. 1889')
        string(name: 'CLUSTER', defaultValue: 'jenkins-tidb-operator-e2e', description: 'the name of the cluster')
        string(name: 'AWS_REGION', defaultValue: 'us-west-2', description: 'the AWS region')
        string(name: 'GINKGO_NODES', defaultValue: '8', description: 'the number of ginkgo nodes')
    }

    environment {
        GIT_REF = ''
        ARTIFACTS = "${env.WORKSPACE}/artifacts"
    }

    stages {
        stage("Prepare") {
            steps {
                // The declarative model for Jenkins Pipelines has a restricted
                // subset of syntax that it allows in the stage blocks. We use
                // script step to bypass the restriction.
                // https://jenkins.io/doc/book/pipeline/syntax/#script
                script {
                    GIT_REF = params.GIT_REF
                    if (params.PR_ID != "") {
                        GIT_REF = "refs/remotes/origin/pr/${params.PR_ID}/head"
                    }
                }
                echo "env.NODE_NAME: ${env.NODE_NAME}"
                echo "env.WORKSPACE: ${env.WORKSPACE}"
                echo "GIT_REF: ${GIT_REF}"
                echo "ARTIFACTS: ${ARTIFACTS}"
            }
        }

        stage("Checkout") {
            steps {
                checkout scm: [
                        $class: 'GitSCM',
                        branches: [[name: GIT_REF]],
                        userRemoteConfigs: [[
                            credentialsId: 'github-sre-bot-ssh',
                            refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*',
                            url: "${params.GIT_URL}",
                        ]]
                    ]
            }
        }

        stage("Run") {
            steps {
                withCredentials([
                    string(credentialsId: 'TIDB_OPERATOR_AWS_ACCESS_KEY_ID', variable: 'AWS_ACCESS_KEY_ID'),
                    string(credentialsId: 'TIDB_OPERATOR_AWS_SECRET_ACCESS_KEY', variable: 'AWS_SECRET_ACCESS_KEY'),
                ]) {
                    sh """
                    #!/bin/bash
                    export PROVIDER=eks
                    export CLUSTER=${params.CLUSTER}
                    export AWS_REGION=${params.AWS_REGION}
                    export GINKGO_NODES=${params.GINKGO_NODES}
                    export REPORT_DIR=${ARTIFACTS}
                    echo "info: try to clean the cluster created previously"
                    ./ci/aws-clean-eks.sh \$CLUSTER
                    echo "info: begin to run e2e"
                    ./hack/e2e.sh -- --ginkgo.skip='\\[Serial\\]' --ginkgo.focus='\\[tidb-operator\\]'
                    """
                }
            }
        }
    }

    post {
        always {
            dir(ARTIFACTS) {
                archiveArtifacts artifacts: "**", allowEmptyArchive: true
                junit testResults: "*.xml", allowEmptyResults: true
            }
        }
    }
}

// vim: et sw=4 ts=4
