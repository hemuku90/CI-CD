#!groovy

QNX_SYSROOT="/home/rxm/rxdep-qnx/sysroots/i486-pc-nto-qnx6.6.0"
// DB: toolchain file is in the git repo, so the path below is relative to the SRC_DIR
CMAKE_TOOLCHAIN_FILE="cmake/qnx-ntox86.cmake"
// DB: qnx660-env.sh comes from the QNX install
DOCKER_QNX_MOUNT_PATH="/home/reflexion/qnx660"
QNX_BUILD_TYPE = 'Release'
RXDEP_QNX_X86="${QNX_SYSROOT}/usr/local"


def build(PLATFORMS){
    echo "---QNX x86 RxMachine --- "
    // pull docker image from dockerhub
    def myimage_qnx_x86 = docker.image("${env.DOCKERNAME}");
    docker.withRegistry('https://registry.hub.docker.com', 'reflexion-docker-registry-login') {
        myimage_qnx_x86.pull();
        // workaround of the bug to get correct newly pulled image name
        // https://issues.jenkins-ci.org/browse/JENKINS-34276
        myimage_qnx_x86 = docker.image(myimage_qnx_x86.imageName());
    }

    PROTOC_PATH = env.RXDEP_NATIVE + "/bin"
    RXDEP_LIBRARY_PATH = env.RXDEP_NATIVE + "/lib"
    def SRC_DIR = "${workspace}/RxMachine"
    def utils_qnxx86 = load "${workspace}/RxMachine/jenkins/utils.groovy"
    def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
    def version = load "${workspace}/utilities/jenkins/version.groovy"
    def localversion = version.getReleaseNumber()

    def buildSerialNodes = [RxBaseNode, CommonLib, HWLib, Algorithms]
    def buildNodesQNX_x86 = [SysNode,MonitorNode,SafeOpNode,KVCTNode, DosimetryNode, GantryNode, CoolingRingNode, CoolingStandNode, CouchNode, MixNode, Egrt, PetNode,DeployUtils,TestMultipleNodes] //DataAnalysisAppsRecordProcessor removed as race condition with CouchNode

    if(utils_qnxx86.isBuildForPullRequest()) {
        echo "\u2692 Starting build for pull request: #${env.CHANGE_ID}"
        echo "\u2692 Pull request url: ${env.CHANGE_URL}"
        echo "\u2692 BUILD_URL=${env.BUILD_URL}"
        echo "\u2692 BUILD_ID=${env.BUILD_ID}"
    } else {
        echo "\u2692 Starting build for branch: BRANCH_NAME=${env.BRANCH_NAME}"
        echo "\u2692 BUILD_URL=${env.BUILD_URL}"
        echo "\u2692 BUILD_ID=${env.BUILD_ID}"
    }

    //QNX_x86
    stage('D:QNX-x86-Build-Init') {
        myimage_qnx_x86.inside {
            echo "Preparing workspace for build-${PLATFORMS.QNX_x86} ..."
            def shellScript = """
                set +x
                cd ${SRC_DIR}
                export PATH=${PATH}:${PROTOC_PATH}
                export LD_LIBRARY_PATH=${RXDEP_LIBRARY_PATH}
                ./compile_proto.sh
                export LD_LIBRARY_PATH=${RXDEP_QNX_X86}/lib

                mkdir -p build-${PLATFORMS.QNX_x86}
                cd build-${PLATFORMS.QNX_x86}
                """
            shellScript += utils_qnxx86.sourceWorkaround(QNX_ENV_VARS)
            shellScript += """
                cmake -DCMAKE_PREFIX_PATH=${RXDEP_QNX_X86} -DCMAKE_TOOLCHAIN_FILE=${SRC_DIR}/${CMAKE_TOOLCHAIN_FILE} -DQNX_SYSROOT=${QNX_SYSROOT} -DFPGA_RELEASES=${FPGA_RELEASES} -DCMAKE_BUILD_TYPE=${QNX_BUILD_TYPE} -DBUILD_NUMBER=${localversion} -DGIT_COMMIT=${env.GIT_COMMIT} -DGIT_CURRENT_STATE=CLEAN --debug-output --trade-expanded ${SRC_DIR} -DGIT_BRANCH=${env.BRANCH_NAME} > ${PLATFORMS.QNX_x86}.log
                """
            //sh shellScript
            echo "${shellScript}"
            jenkinsHelper.mysh(shellScript)
        }
    }

    stage('QNX-x86-serial-nodes') {
        try {
               utils_qnxx86.buildNode(PLATFORMS.QNX_x86, myimage_qnx_x86, buildSerialNodes, SRC_DIR, utils_qnxx86, RXDEP_QNX_X86)
            }
        catch (exc) {
            echo "exception from QNX-x86-serial-nodes Look at *.err file"
            utils_qnxx86.archiveErrorArtifacts(PLATFORMS.QNX_x86, buildSerialNodes, SRC_DIR)
            currentBuild.result = 'FAILURE'
            throw exc
        }
    }

    stage('QNX-x86-parallel-Nodes') {
        try{
            parallel utils_qnxx86.initBuildTasks(PLATFORMS.QNX_x86, myimage_qnx_x86,buildNodesQNX_x86,SRC_DIR,utils_qnxx86,RXDEP_QNX_X86)
        }
        catch(exc){
            echo "From stage QNX-x86-Build-Nodes Look at {node}.err file"
            utils_qnxx86.archiveErrorArtifacts(PLATFORMS.QNX_x86,buildNodesQNX_x86,SRC_DIR)
            currentBuild.result='FAILURE'
            throw exc
        }
    }

    stage('QNX-x86-Build-DataAnalysisApp') {
        try{
            utils_qnxx86.buildDataAnalysisApp(PLATFORMS.QNX_x86,myimage_qnx_x86,buildNodesQNX_x86,SRC_DIR,utils_qnxx86,RXDEP_QNX_X86)
        }

        catch(exc){
            echo "exception from QNX-x86-Build-DataAnalysisApp. Look at DataAnalysisApp.err file"
            utils_qnxx86.archiveErrorArtifacts(PLATFORMS.QNX_x86,buildNodesQNX_x86,SRC_DIR)
            currentBuild.result='FAILURE'
            throw exc
        }
    }
    stage('D:QNX-Archive-Artifacts') {
        utils_qnxx86.arcArtifacts(PLATFORMS.QNX_x86,buildNodesQNX_x86,SRC_DIR)
    }
    //QNX_x86
}
return this
