#!groovy

//set true/false to build your component. Revert back when you are ready to raise the PR.
//Default true group
def buildqnxarm = true
def buildqnxx86 = true
def buildubuntu = true
def buildrxsuite = true
def buildrxgateway = true
def buildrxplanner = true
def buildrxdeploy = true
def zipartifact = true
def gittag = true
//Default false group
def ziprxDB = false
def s3upload = false
def runci = false
def executeunittest = false

// These flags are default set to false. If you want to turn it on contact DevOps team.
rxsuiteBuild = [
    result: 'UNKNOWN'
]

// buildMap enables private branches to build only a subset of the system to speedup the build
// process. The PR builds will still build the full system.
//
// format:
//    [~"branch-regexp": ["buildStage1", "buildStage2", ...]]
// build stage must be a list including one or more of the following stages:
//     RxDB, RxSuite, E2E, Ubuntu, QNX x86, QNX arm, RxPlannerBackend, RxMachineDeploy, RxGateway
//
buildMap = [
    [~"^yv/machine/.*", ["QNX x86", "QNX arm"]],
    [~"^yv/backend/.*", ["RxPlannerBackend"]]
]

enum PLATFORMS {UBUNTU_x86,QNX_x86,QNX_arm}
def howmanydigitsofcommithash = 10
def utils=null

def checkoutCode() {
    def scmVar = checkout([
        $class : 'GitSCM',
        branches : scm.branches,
        extensions : scm.extensions + [
            [$class: 'CleanBeforeCheckout'],
            [$class: 'GitLFSPull'],
            [$class: 'CheckoutOption', timeout: 30],
            [$class: 'CloneOption', timeout: 30]
        ],
        userRemoteConfigs: scm.userRemoteConfigs
    ])
    return scmVar
}

/**
 * Check if the current build should execute the pipline for pull request
 *
 * @return "true" in case of pull request pipline, otherwise "false"
 */
def isBuildForPullRequest() {
    return env.CHANGE_ID != null
}

/**
 * Check if the current build should execute the pipline for pull request merged with master branch
 *
 * @return "true" in case of:
 *           - build regular branch
 *           - pull request with label [Ready For Build]
 */
def checkForPullRequest() {
    def result = true; // if not pr -> regular branch
    if (isBuildForPullRequest()) {
        result = false; // don't build if PR & doesn't have the label
        for (label in pullRequest['labels']) {
            if( label == "Ready For Build") {
                result = true; // if pr has desired lebel -> build
            }
        }
        if (!result) {
            currentBuild.result = 'ABORTED'
            error('Aborting build ... [Ready For Build] label not set ...')
        }
    }
    return result;
}

def shouldWeBuild(stage) {
    def result = true;
    def selective = false;
    if (isBuildForPullRequest()) {
        return true;
    }
    else {
        result = false; // don't build if PR & doesn't have the label
        for (b in buildMap) {
            if (env.BRANCH_NAME ==~ b[0]) {
                selective = true
                if (b[1].contains(stage)) {
                    result = true
                }
                else {
                    result = false
                }
            }
        }
        // we build (result = true) if nothing is found in the buildMap (selective=false)
        // or the buildMap has a successful match
        if (!selective) {
            result = true;
        }
        echo "shouldWeBuild STAGE: ${stage} = ${result}"
        return result;
    }
}

/* Key branches are the one in which all tests run,rxdb is zipped */
def isItAnKeyBranch() {
    return (env.BRANCH_NAME == "master"
        || env.BRANCH_NAME == "stable-master"
        || env.BRANCH_NAME ==~ /integration\/.*/
        || env.BRANCH_NAME ==~ /integration\-.*/
        || env.BRANCH_NAME ==~ /int\/.*/
        || env.BRANCH_NAME ==~ /release\/.*/
        || env.BRANCH_NAME ==~ /hotfix\/.*/
        || env.BRANCH_NAME ==~ /rc\/.*/
    )
}

/***
    Kill all previous jobs. Ensure only one job runs at a time.
***/
def killall_jobs() {
    def jobname = env.JOB_NAME
    def buildnum = env.BUILD_NUMBER.toInteger()
    echo "From kill all jobs"
    echo "${jobname}"
    def job = Jenkins.instance.getItemByFullName(jobname)
    for (build in job.builds) {
        if (!build.isBuilding()){
            continue;
        }
        if (buildnum == build.getNumber().toInteger()){
            continue;
        }
        echo "Kill task = ${build}"
        build.doStop();
    }
}

pipeline {
    agent none

    parameters {
        booleanParam(defaultValue: buildqnxarm, description: '', name: 'buildqnxarm')
        booleanParam(defaultValue: buildqnxx86, description: '', name: 'buildqnxx86')
        booleanParam(defaultValue: buildubuntu, description: '', name: 'buildubuntu')
        booleanParam(defaultValue: buildrxsuite, description: '', name: 'buildrxsuite')
        booleanParam(defaultValue: buildrxgateway, description: '', name: 'buildrxgateway')
        booleanParam(defaultValue: buildrxplanner, description: '', name: 'buildrxplanner')
        booleanParam(defaultValue: buildrxdeploy, description: '', name: 'buildrxdeploy')
        booleanParam(defaultValue: ziprxDB, description: '', name: 'ziprxDB')
        booleanParam(defaultValue: zipartifact, description: '', name: 'zipartifact')
        booleanParam(defaultValue: s3upload, description: '', name: 's3upload')
        booleanParam(defaultValue: executeunittest, description: '', name: 'executeunittest')
        booleanParam(defaultValue: runci, description: '', name: 'runci')
    }

    environment {
        RXDEP_NATIVE="/home/rxm/rxdep-native"
        FPGA_RELEASES="/home/workspace/FPGA_releases"
        NODE_CONFIG_DIR="MachineData"
        QNX_ENV_VARS="/home/reflexion/qnx660/qnx660-env.sh"
        jenkinsMasterIp = "172.31.34.102"
        DOCKERNAME='reflexion/rxmachine'
    }

    options {
            skipDefaultCheckout()
            // Keep only 3 builds for all branches.
            buildDiscarder(logRotator(numToKeepStr:'3'))
            // More time needed for nightly/* builds
            // TImeouts: 300 min for nightly, 90 min for regular builds
            timeout(time: (env.BRANCH_NAME ==~ /nightly\/.*/ || env.BRANCH_NAME == 'stable-master') ? 300 : 90,
                    unit: 'MINUTES')
    }
    stages {
        stage('Cleanup and Enforce flags') {
            steps {
                script {
                    //If a job is already running for this branch !!! stop them.
                    killall_jobs()
                    //Enforce all flags for PR, Say if build is triggered manually with parameters we should not slip any checks.
                    if(isItAnKeyBranch() || isBuildForPullRequest()) {
                        buildqnxarm = true
                        buildqnxx86 = true
                        buildubuntu = true
                        buildrxsuite = true
                        buildrxgateway = true
                        buildrxplanner = true
                        buildrxdeploy = true
                        ziprxDB = true
                        zipartifact=true
                        executeunittest=true
                    }
                }
            }
        }

        stage('Parallel Build') {
            failFast false
            parallel {
                stage('RxDB') {
                   when {
                        beforeAgent true
                        expression {
                            return (params.ziprxDB || isItAnKeyBranch() || isBuildForPullRequest())

                        }
                    }
                    agent {
                        node {
                            label 'RxMachine-build-slave'
                            customWorkspace '/home/ec2-user/workspace/RxOne/'
                        }
                    }
                    steps {
                        script {
                            sh """
                                sudo chown ec2-user:ec2-user -R ./
                            """
                            checkoutCode()
                            def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
                            def branchName = jenkinsHelper.modifyBranchName(env.BRANCH_NAME)
                            def dbArtifactName = "${branchName}-${BUILD_NUMBER}-RxDB.zip"

                            sh """
                                cd ${workspace}
                                zip -r ${dbArtifactName} RxDB/base \
                                    RxDB/deliveryDevice \
                                    RxDB/vv-ptsource \
                                    RxDB/vv-ac-homo \
                                    RxDB/usability \
                                    RxDB/id_hash.json
                            """

                            archiveArtifacts allowEmptyArchive: true, artifacts: dbArtifactName , fingerprint: true
                        }
                    }
                    post{
                        unstable{
                            echo "Common node build unstable"
                        }

                    }
                }
                stage('RxSuite') {
                    when {
                        beforeAgent true
                        expression {
                            return  params.buildrxsuite && checkForPullRequest() && shouldWeBuild('RxSuite');
                        }
                    }
                    agent {
                        node {
                            label 'RxMachine-build-slave'
                            customWorkspace '/home/ec2-user/workspace/RxOne/'
                        }
                    }
                    steps {
                        script {
                            echo "--- RxOne ---"

                            def workspacerxsuite = pwd()
                            echo "Initializing ..."

                            sh """
                                sudo chown ec2-user:ec2-user -R ./
                            """

                            script {
                                def scmVar = checkoutCode()
                                env.GIT_COMMIT = "${scmVar.GIT_COMMIT}".substring(0,howmanydigitsofcommithash)
                                env.NODEJS_HOME = "${tool 'Node 8.x'}"
                                env.PATH="${env.NODEJS_HOME}/bin:${env.PATH}"
                            }

                            echo sh(script: 'env|sort', returnStdout: true)
                            sh """
                                npm --version
                            """
                            echo "RxSuite"
                            def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
                            def version = load "${workspace}/utilities/jenkins/version.groovy"

                            jenkinsHelper.updatePackageVersionTask(workspacerxsuite, jenkinsHelper.modifyBranchName(env.BRANCH_NAME), env.BUILD_NUMBER, env.GIT_COMMIT)
                            script {
                                    def artifactVersion = version.getReleaseNumber()
                                    sh """
                                        echo '{"name": "RxSuite", "version": "${artifactVersion}"}' > ${workspacerxsuite}/RxSuite/src/rx-suite-version.json
                                        echo '{"name": "RxTools", "version": "${artifactVersion}"}' > ${workspacerxsuite}/RxSuite/src/rx-tools-version.json
                                    """
                                def RxSuite = load "${workspacerxsuite}/RxSuite/jenkins/Jenkins.groovy"
                                RxSuite.RxSuite(RxSuite.&apps, RxSuite.&npmBuildProdAndPackage)
                            }
                            stage('RxSuite: Archive artifact') {
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'RxSuite/*.AppImage, RxSuite/*.exe, RxSuite/*.tar.gz', fingerprint: true
                            }
                        }
                    }
                    post {
                        failure {
                            echo "RxSuite failed"
                            script {
                                rxsuiteBuild.result = 'FAILED'
                            }
                        }
                    }
                }
                stage('Ubuntu') {
                    when {
                        beforeAgent true
                        expression {
                            return params.buildubuntu && checkForPullRequest() && shouldWeBuild('Ubuntu');
                        }
                    }
                    agent {
                        node {
                            label 'RxMachine-build-slave'
                            customWorkspace '/home/ec2-user/workspace/RxOne/'
                        }
                    }
                    steps {
                        script {
                            sh """
                                sudo chown ec2-user:ec2-user -R ./
                            """
                            checkoutCode()
                            env.NODEJS_HOME = "${tool 'Node 8.x'}"
                            env.PATH="${env.NODEJS_HOME}/bin:${env.PATH}"
                            def build = load "${workspace}/RxMachine/jenkins/linuxJenkins.groovy"
                            utils = load "${workspace}/RxMachine/jenkins/utils.groovy"
                            build.build(PLATFORMS)

                            utils.mergemachinedatasimuxlab()

                            stage('Archive merged artifact') {
                                archiveArtifacts allowEmptyArchive: true, artifacts: '**/merged/*.json, **/merged/*.csv, **/merged/*.proto', fingerprint: true
                                archiveArtifacts allowEmptyArchive: true, artifacts: '**/merged-uxlab/*.json, **/merged-uxlab/*.csv, **/merged/*.proto', fingerprint: true
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'utilities/start-nodes/start-nodes-sim.sh', fingerprint: true
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'utilities/start-nodes/start-nodes-sim-couch.sh', fingerprint: true
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'SimData/kvct-playback/*.dat, SimData/kvct-playback/*.bin', fingerprint: true
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'SimData/simulated-pet/*.bin', fingerprint: true

                            }
                        }
                    }
                    post{
                        unstable{
                            echo "UBUNTU_x86-Build-Nodes build unstable"
                            script {
                                utils.uploadUnitTestexe(PLATFORMS.UBUNTU_x86)
                            }
                        }

                    }
                }
                stage('QNX x86') {
                    when {
                        beforeAgent true
                        expression {
                            return params.buildqnxx86 && checkForPullRequest() && shouldWeBuild('QNX x86');
                        }
                    }
                    agent {
                        node {
                            label 'RxMachine-build-slave'
                            customWorkspace '/home/ec2-user/workspace/RxOne/'
                        }
                    }
                    steps {
                        script {
                            sh """
                                sudo chown ec2-user:ec2-user -R ./
                            """
                            checkoutCode()
                            def build = load "${workspace}/RxMachine/jenkins/qnx86Jenkins.groovy"
                            utils = load "${workspace}/RxMachine/jenkins/utils.groovy"
                            build.build(PLATFORMS)
                        } //script
                    }//stage
                     post{
                        unstable{
                            echo "QNX_x86-Build-Nodes build unstable"
                            script {
                                utils.uploadUnitTestexe(PLATFORMS.QNX_x86)
                            }

                        }
                    }
                }
                stage('QNX arm') {
                    when {
                        beforeAgent true
                        expression {
                            return params.buildqnxarm && checkForPullRequest() && shouldWeBuild('QNX arm');
                        }
                    }
                    agent {
                        node {
                            label 'RxMachine-build-slave'
                            customWorkspace '/home/ec2-user/workspace/RxOne/'
                        }
                    }
                    steps {
                        script {

                            sh """
                                sudo chown ec2-user:ec2-user -R ./
                            """
                            checkoutCode()
                            def build = load "${workspace}/RxMachine/jenkins/qnxArmJenkins.groovy"
                            utils = load "${workspace}/RxMachine/jenkins/utils.groovy"
                            build.build(PLATFORMS)

                        } //script
                    } //steps
                    post{
                        unstable{
                            echo "QNX_ARM-Build-Nodes build unstable"
                            script {
                                utils.uploadUnitTestexe(PLATFORMS.QNX_arm)
                                }
                        }
                    }

                } //stage
                stage('RxPlannerBackend') {
                    when {
                        beforeAgent true
                        expression {
                            return  params.buildrxplanner && checkForPullRequest() && shouldWeBuild('RxPlannerBackend');
                        }
                    }

                    agent {
                        node {
                            label 'RxMachine-build-slave'
                            customWorkspace '/home/ec2-user/workspace/RxOne/'
                        }
                    }

                    steps {
                        script {
                            sh """
                            sudo chown ec2-user:ec2-user -R ./
                            """
                            checkoutCode();
                            def plannerBackend = load "${workspace}/PlannerBackend/jenkins/plannerBackend.groovy"
                            plannerBackend.build()
                        }
                    }
                }

                stage('RxMachineDeploy') {
                    when {
                        beforeAgent true
                        expression {
                            return  params.buildrxdeploy && checkForPullRequest() && shouldWeBuild('RxMachineDeploy');
                        }
                    }
                    agent {
                        node {
                            label 'RxMachine-build-slave'
                            customWorkspace '/home/ec2-user/workspace/RxOne/'
                        }
                    }
                    steps {
                        script {
                            echo "RxMachineDeploy"
                            def scmVar = checkoutCode()
                            def workspaceDeploy = pwd()
                            def jenkinsHelper = load "${workspaceDeploy}/utilities/jenkins/jenkinsHelper.groovy"
                            sh """
                                sudo chown ec2-user:ec2-user -R ./
                            """
                            env.GIT_COMMIT = "${scmVar.GIT_COMMIT}".substring(0,howmanydigitsofcommithash)
                            env.NODEJS_HOME = "${tool 'Node 8.x'}"
                            env.PATH="${env.NODEJS_HOME}/bin:${env.PATH}"
                            echo sh(script: 'env|sort', returnStdout: true)
                            sh """
                                npm --version
                            """
                            jenkinsHelper.createReleaseVersionFile(workspaceDeploy)
                            jenkinsHelper.updatePackageVersionTask(workspaceDeploy, jenkinsHelper.modifyBranchName(env.BRANCH_NAME), env.BUILD_NUMBER, env.GIT_COMMIT)

                            script {
                                def RxMachineDeploy = load "${workspaceDeploy}/utilities/rxmachine-deploy/Jenkins.groovy"
                                RxMachineDeploy.RxMachineDeploy()
                            }

                            stage('RxMachineDeploy: Archive artifact') {
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'utilities/rxmachine-deploy/version.json, utilities/rxmachine-deploy/dist/rxdeploy, utilities/rxmachine-deploy/dist/config/nodes.json', fingerprint: true
                            }
                        }
                    }
                }
                stage('RxGateway') {
                    when {
                        beforeAgent true
                        expression {
                            return  params.buildrxgateway && checkForPullRequest() && shouldWeBuild('RxGateway');
                        }
                    }
                    agent {
                        node {
                            label 'RxMachine-build-slave'
                            customWorkspace '/home/ec2-user/workspace/RxOne/'
                        }
                    }
                    steps {
                        script {
                            echo "RxGateway"
                            def workspacegateway = pwd()

                            sh """
                                sudo chown ec2-user:ec2-user -R ./
                            """

                            def scmVar = checkoutCode()
                            env.GIT_COMMIT = "${scmVar.GIT_COMMIT}".substring(0,howmanydigitsofcommithash)
                            env.NODEJS_HOME = "${tool 'Node 8.x'}"
                            env.PATH="${env.NODEJS_HOME}/bin:${env.PATH}"
                            echo sh(script: 'env|sort', returnStdout: true)
                            sh """
                                npm --version
                            """
                            def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"

                            jenkinsHelper.updatePackageVersionTask(workspacegateway, jenkinsHelper.modifyBranchName(env.BRANCH_NAME), env.BUILD_NUMBER, env.GIT_COMMIT)

                            script {
                                def RxGateway = load "${workspacegateway}/RxGateway/jenkins/Jenkins.groovy"
                                RxGateway.RxGateway(rxsuiteBuild,buildrxsuite)
                            }

                            sh """
                                cp -r ${workspacegateway}/RxGateway/dist/* ${workspacegateway}/RxGateway/
                                mv ${workspacegateway}/RxGateway/rx-gateway-linux ${workspacegateway}/RxGateway/rx-gateway
                                cp ${workspace}/utilities/rxmachine-deploy/release-config.example.json ${workspace}/RxGateway/release-config.json
                                cp ${workspacegateway}/RxGateway/dist-rxdb-scaffold/rx-gateway-linux ${workspacegateway}/RxGateway/rxdb-scaffold
                                #Needed as rxdep-native is created at root level
                                sudo chown ec2-user:ec2-user -R ./RxGateway
                            """

                            def artifacts = [
                                'RxGateway/rx-gateway',
                                'RxGateway/rxdb-scaffold',
                                'RxGateway/zmq.node',
                                'RxGateway/diskusage.node',
                                'RxGateway/guardian.node',
                                'RxGateway/binding.node',
                                'RxGateway/main.bundle.js',
                                'RxGateway/app-settings.json',
                                'RxGateway/lib/*',
                                'RxGateway/rxdep-native/lib/*.so*',
                                'RxGateway/release-config.json'
                            ]

                            stage('Achive artifact'){
                                archiveArtifacts allowEmptyArchive: true, artifacts: artifacts.join(", "), fingerprint: true
                            }

                        }
                    }//steps
                } // RxGateway stage
            } //End of Parallel block
        } // End of Parallel stage
        stage('Start CI tests') { //Execute CI only for master branch.
            when {
                beforeAgent true
                expression {
                    return (currentBuild.result != 'FAILURE'
                            && currentBuild.result != 'UNSTABLE'
                            && (env.BRANCH_NAME == 'stable-master'
                                || env.BRANCH_NAME == 'master'
                                || isBuildForPullRequest()
                                || params.runci == true)
                    )
                }
            }
            agent {
                node {
                    label 'ubuntu_ci_slave'
                    customWorkspace "/home/ubuntu/workspace/RxOne"
                }
            }

            steps {
                script {
                        echo currentBuild.result
                        checkoutCode();
                        def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
                        def modifyBranchName = jenkinsHelper.modifyBranchName(env.BRANCH_NAME)
                        def source = "/var/lib/jenkins/jobs/RxOne/branches/${modifyBranchName}*/builds/${BUILD_NUMBER}/archive"
                        def artifactList = ["MachineData", "PlannerBackend", "RxMachine/build-UBUNTU_x86", "RxGateway", "RxSuite","${modifyBranchName}-${BUILD_NUMBER}-RxDB.zip"]
                        sh """
                            #kill any process running and then remove the contents
                            # chmod a+x ${workspace}/utilities/start-nodes/kill-all-nodes.sh
                            # cd ${workspace}/utilities/start-nodes
                            #./kill-all-nodes.sh
                            mkdir -p $HOME/ci
                            rm -rf $HOME/ci/*
                        """
                        jenkinsHelper.copyArtifactOnSlave(source, artifactList)
                        jenkinsHelper.downloadRxdepFromS3()
                        jenkinsHelper.executeRxTesttools()//execute RxTestTools e2e
                        archiveArtifacts allowEmptyArchive: true, artifacts: '**/RxSuite/e2e/testtoolscreenshots/*.*, **/RxSuite/e2e/testtoolreports/*.xml', fingerprint: true
                        junit allowEmptyResults: true, testResults: "**/RxSuite/e2e/testtoolreports/*.xml"
                        jenkinsHelper.executeRxSuite()//execute RxSuite e2e
                } //End of script

            } //End of steps

            post {
                always{
                        archiveArtifacts allowEmptyArchive: true, artifacts: '**/RxSuite/e2e/screenshots/*.*, **/RxSuite/e2e/reports/*.xml', fingerprint: true
                        junit allowEmptyResults: true, testResults: "**/RxSuite/e2e/reports/*.xml"
                        }
            } //End of Post
        }// End of CI tests block

        stage('zip artifact') {
            when {
                beforeAgent true
                expression {
                    return (params.zipartifact && currentBuild.result != 'FAILURE' )
                }
            }
            agent {
                node {
                    label 'master'
                    customWorkspace '/var/lib/jenkins/workspace/RxOne'
                }
            }

            steps {
                script {
                    checkoutCode();
                    def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
                    jenkinsHelper.zipReleaseArtifact()
                    jenkinsHelper.zipTestToolArtifact()
                    jenkinsHelper.zipDeployArtifact()
                    jenkinsHelper.zipSimArtifact()
                    jenkinsHelper.zipUxlabArtifact()
                    jenkinsHelper.zipOthersArtifact()
                    jenkinsHelper.bundleBuildLogs()
                } //End of script
            } //End of steps
        }// End of zip artifact stage

        stage('Cleanup artifact on failure') {
            when {
                beforeAgent true
                expression {
                    return (currentBuild.result == 'FAILURE' && currentBuild.result == 'UNSTABLE' )
                }
            }
            agent {
                node {
                    label 'master'
                    customWorkspace '/var/lib/jenkins/workspace/RxOne'
                }
            }

            steps {
                script {
                    checkoutCode();
                    def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
                    jenkinsHelper.deleteArtifacts()
                } //End of script
            } //End of steps
        }// End of delete artifact stage

        stage('Upload Artifact on s3') {
            when {
                beforeAgent true
                expression {
                    return (currentBuild.result != 'FAILURE'
                            && currentBuild.result != 'UNSTABLE'
                            && (isItAnKeyBranch() || params.s3upload == true)
                        )
                }
            }
            agent {
                node {
                    label 'master'
                    customWorkspace '/var/lib/jenkins/workspace/RxOne'
                }
            }

            steps {
                script {
                    checkoutCode();
                    def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
                    jenkinsHelper.uploadArtifactOnS3()
                } //End of script
            } //End of steps
        }// End of upload artifact stage

        stage('git tag') {
            when {
                beforeAgent true
                expression {
                    return (gittag == true
                        && checkForPullRequest()
                        && currentBuild.result != 'FAILURE'
                        && currentBuild.result != 'UNSTABLE'
                        && env.BRANCH_NAME ==~ /release\/.*/
                        )
                }
            }
            agent {
                node {
                    label 'git-tag'
                    customWorkspace '/home/ec2-user/workspace/RxOne/'
                }
            }

            steps {
                script {

                    sh """
                        sudo chown ec2-user:ec2-user -R ./
                       """
                    checkoutCode()
                    def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
                    def tagName = jenkinsHelper.tagVersion()

                    sh """
                        cd ${workspace}
                         git remote set-url origin git@github.com:ReflexionMed/RxOne.git
                         DATE=`date '+%Y-%m-%d %H:%M:%S'`
                         git tag -a ${tagName} -m 'tag creation date: '`date +"%m_%d_%Y"`
                         git push origin tag ${tagName}
                        """

                } //End of script
            } //End of steps
        }// End of git tag

    } // End of Stages block
}
