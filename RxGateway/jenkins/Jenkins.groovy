def mysh(cmd){
    sh('#!/bin/sh -e\n' + cmd)
}
def RxGateway(rxsuiteBuild,buildrxsuite){
    // This can include multiple test files
    // e.g unitTestFileList = 'server/test/utils/testUtils.js server/test/sendPlan/testSendPlanBinaryFiles.js'
    // NOTE: the path is relative to RxGateway root.
    unitTestFileList = 'server/test/utils/testUtils.js'

    // pull docker image from dockerhub
    def myimage = docker.image("${env.DOCKERNAME}");
    docker.withRegistry('https://registry.hub.docker.com', 'reflexion-docker-registry-login') {
        myimage.pull();
        // workaround of the bug to get correct newly pulled image name
        // https://issues.jenkins-ci.org/browse/JENKINS-34276
        myimage = docker.image(myimage.imageName());
    }


    def jobName = 'RxGateway'
    def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
    def workspace_rxgateway = "${workspace}/RxGateway/"

    stage("${jobName}: Install") {
        echo "Installing node_modules ..."

        // DB: mounting volume to avoid No user exists for uid
        myimage.inside('-v /etc/passwd:/etc/passwd') {
            withEnv([
                /* Override the npm cache directory to avoid: EACCES: permission denied, mkdir '/.npm' */
                'npm_config_cache=npm-cache',
                /* set home to our current directory because other bower
                * nonsense breaks with HOME=/, e.g.:
                * EACCES: permission denied, mkdir '/.config'
                */
                // 'HOME=.',
            ]) {
                // DB:  export user to prevent WARN EACCES user "undefined" does not have permission to access the dev dir "/home/ec2-user/.node-gyp/7.10.0"
                sshagent(credentials: ['jenkins-github-ssh']) {
                    // ssh -o StrictHostKeyChecking=no -T git@github.com
                    ansiColor('xterm') {
                        def shellscript = """
                            set +x
                            cd ${workspace_rxgateway}
                            export PKG_CONFIG_PATH=${env.RXDEP_NATIVE}/lib/pkgconfig
                            export USER=ec2-user
                            npm install
                        """
                        mysh(shellscript)

                    }
                }
            }
        }
    }

    stage("${jobName}: Lint") {
        echo "Linting RxGateway ..."
        myimage.inside('--user root') {
            ansiColor('xterm') {
                sh """
                    cd ${workspace_rxgateway}
                    npm run lint
                """
            }
        }
    }

    stage("${jobName}: copy rxdep-native") {
        echo "Copy rxdep-native ..."
        myimage.inside('--user root') {
            ansiColor('xterm') {
                sh """
                    cd ${workspace_rxgateway}
                    pwd
                    cp -r /home/rxm/rxdep-native .
                    ls  -al
                """
            }
        }
    }

    stage("${jobName}: UnitTest") {
        if(!jenkinsHelper.executeUnitTest()){
            echo "Skipping UnitTests RxGateway ..."
            return true
        }
        echo "Starting UnitTests RxGateway ..."

        myimage.inside('--user root') {
            sh """
                cd ${workspace_rxgateway}
                sed -i 's/testOffline: false/testOffline: true/' data/mocks/index.js
            """

            ansiColor('xterm') {
                def shellscript = """
                    set +x
                    nohup mongod &
                    cd ${workspace_rxgateway}
                    export LD_LIBRARY_PATH=${env.RXDEP_NATIVE}/lib
                    export NODE_CONFIG_DIR=${workspace_rxgateway}/../MachineData/
                    export XUNIT_FILE=${workspace_rxgateway}/mocha-report.xml
                    npm run jenkins-test
                    cd ${workspace}/utilities/codecov
                    chmod a+x uploadcodecov.sh
                    cd ${workspace_rxgateway}
                    ${workspace}/utilities/codecov/uploadcodecov.sh
                """
                mysh(shellscript)
            }
        }
        junit allowEmptyResults: true, testResults: "**/mocha-report.xml"
    }

    stage("${jobName}: Build") {
        echo "Building RxGateway ..."

        myimage.inside('--user root') {
            ansiColor('xterm') {
                def shellscript = """
                    set +x
                    npm install -g bower
                    cd ${workspace_rxgateway}/client/console
                    bower install --allow-root

                    cd ${workspace_rxgateway}
                    export NODE_CONFIG_DIR=${workspace}/MachineData/
                    npm run build
                """
                mysh(shellscript)
            }
        }
    }

    stage("${jobName}: Package") {
        echo "Packaging RxGateway ..."

        myimage.inside('--user root') {
            ansiColor('xterm') {
                sh """
                    cd ${workspace_rxgateway}
                    npm run dist
                """
            }
        }
    }

    stage("${jobName}: Build rxdb-scaffold") {
        echo "Building rxdb-scaffold ..."

        myimage.inside('--user root') {
            ansiColor('xterm') {
                def shellscript = """
                    set +x

                    cd ${workspace_rxgateway}
                    export NODE_CONFIG_DIR=${workspace}/MachineData/
                    npm run build:rxdb-scaffold
                """
                mysh(shellscript)
            }
        }
    }

    stage("${jobName}: Package rxdb-scaffold") {
        echo "Packaging rxdb-scaffold ..."

        myimage.inside('--user root') {
            ansiColor('xterm') {
                sh """
                    cd ${workspace_rxgateway}
                    npm run dist:rxdb-scaffold
                """
            }
        }
    }
}

return this
