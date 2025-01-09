def withPod(body) {
  podTemplate(label: "pod", serviceAccount: "jenkins", containers: [
      containerTemplate(name: "docker", image: "docker:27.4.1", command: "cat", ttyEnabled: true),
      containerTemplate(name: "kubectl", image: "lachlanevenson/k8s-kubectl", command: "cat", ttyEnabled: true)
    ],
    volumes: [
      hostPathVolume(mountPath: "/var/run/docker.sock", hostPath: "/var/run/docker.sock"),
    ]
 ) { body() }
}

withPod {
  node('pod') {
    def tag = "${env.BRANCH_NAME}.${env.BUILD_NUMBER}"
    def service = "market-data:${tag}"
    def tagToDeploy = "192.168.49.2:5000/${service}" 

    // sh("git clone https://github.com/zakaria-shahen/microservices-in-action-book")
    checkout scm 

    container("docker") {
            stage("Build") {
                def myImage = docker.build(tagToDeploy, "chapter-10/market-data")
                // myImage.push() // if you need to push to insecure registries use ctr not docker.
                    /*
                    // ref: https://stackoverflow.com/a/67310470/15107127
                    sh("docker save -o image.tar ${tagToDeploy}")
                    sh("ctr image import image.tar")
                    sh("ctr image push ${tagToDeploy}")
                    */                           
            }

            stage("Test") {
                sh("docker run --rm ${tagToDeploy} python setup.py test")
            }   
            
            stage("Deploy") {
                sh("sed -i.bak 's#BUILD_TAG#${tagToDeploy}#' ./chapter-10/deploy/*.yml")
                container("kubectl") {
                    sh("kubectl --namespace=staging apply -f chapter-10/deploy/")
                }
            }

            stage('Approve release?') {
                 input message: "Release ${tagToDeploy} to production?"
            }

            stage('Deploy to production') {
                sh("sed -i.bak 's#BUILD_TAG#${tagToDeploy}#' ./chapter-10/deploy/*.yml")
                sh("sed -i.bak 's#30625#30626#' ./chapter-10/deploy/market-data-service.yml")
                container('kubectl') {
                    sh("kubectl --namespace=production apply -f chapter-10/deploy/")

                }
            }

            stage('Deploy canary') {
                sh("sed -i.bak 's#30626#30627#' ./chapter-10/deploy/market-data-service.yml")
                container('kubectl') {
                    sh("kubectl --namespace=canary apply -f chapter-10/deploy/")
                    // deploy.toKubernetes(tagToDeploy, 'canary', 'market-data-canary')
                    try {
                        input message: "Continue releasing ${tagToDeploy} to production?" 
                    } catch (Exception e) {
                        sh("kubectl --namespace canary rollout undo deployment/market-data")
                    }
                }
            }           
        }
    } 
}