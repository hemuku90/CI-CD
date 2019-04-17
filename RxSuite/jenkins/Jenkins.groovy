def npmBuildProdAndPackage(apps) {
    def tasks = [:]
    def idx = apps.size()
    for (int i = 0; i < idx; i++) {
        def app = apps[i]
        def appLabel = app.label
        def bin = app.bin
        tasks[appLabel] = {
            stage("${appLabel}: Build") {
                echo "Building ${appLabel} ..."
                ansiColor('xterm') {
                    sh """
                        cd ${workspace}/RxSuite
                        npm run build:prod:${bin} -- --progress=false
                    """

                    if (appLabel == 'RxSuite') {
                        // stash the build output for RxGateway
                        dir("${workspace}/RxSuite") {
                            stash includes: 'dist-rx-suite/**/*', name: 'rx-suite-build'
                        }
                    }
                }
            }

            stage("${appLabel}: Package") {
                echo "Starting Packaging ${appLabel} ..."
                ansiColor('xterm') {
                    sh """
                        cd ${workspace}/RxSuite
                        docker run --rm -v ${workspace}/RxSuite:/project -v ${workspace}/RxSuite/node_modules:/project/node_modules -v ~/.cache/electron:/root/.cache/electron -v ~/.cache/electron-builder:/root/.cache/electron-builder electronuserland/electron-builder:wine npm run dist:${bin}
                        cp ./app-${bin}/dist/${bin}-*-x86_64.AppImage ./${bin}.AppImage
                        cp ./app-${bin}/dist/${bin}-*-mac.tar.gz ./${bin}-mac.tar.gz
                        cp ./app-${bin}/dist/${bin}\\ Setup\\ *.exe ./${bin}.exe
                    """
                }
            }
        }
    }
    return tasks
}

def apps() {
    return  [[
        label:'RxSuite',
        bin: 'rx-suite'
    ], [
        label:'RxTools',
        bin: 'rx-tools'
    ]]
}

def RxSuite(packageApps, npmBuildProdAndPackage) {
    jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
    stage("RxSuite: Install") {
        echo "Initializing RxSuite ..."
        ansiColor('xterm') {
            sh """
                cd ${workspace}/RxSuite
                export USER=ec2-user
                npm install
                npm install 7zip-bin-linux
                ls -l src/modules/
                mkdir -p latest
            """
        }
    }

    // stage('tslint RxSuite') {
    //     // DB: This will cause build to fail in CI env, if there's any lint errors
    //     echo "Linting RxSuite ..."
    //     sh """
    //         cd ${workspace}
    //         npm run lint
    //     """
    // }

    stage("RxSuite: Build and Package") {
        script {
            parallel npmBuildProdAndPackage(packageApps())
        }
    }

    stage("RxSuite: Unit Test") {
        if(!jenkinsHelper.executeUnitTest()){
            echo "Skipping UnitTests RxSuite ..."
            return true
        }
        echo "Starting UnitTests RxSuite ..."
        ansiColor('xterm') {
            def shellscript = """
                set a+x
                cd ${workspace}/RxSuite
                docker run --rm -v ${workspace}/RxSuite:/project -v ${workspace}/RxSuite/node_modules:/project/node_modules -v ${workspace}/RxSuite/junit:/project/junit electronuserland/electron-builder:wine npm run test:ci -- --sourcemap=false --watch=false --progress=false
                cd ${workspace}/utilities/codecov
                chmod a+x uploadcodecov.sh
                cd ${workspace}/RxSuite/junit
                ${workspace}/utilities/codecov/uploadcodecov.sh
            """
            sh('#!/bin/sh -e\n' + shellscript)
        }
        junit allowEmptyResults: true, testResults: "RxSuite/junit/*.xml"
    }
}

return this