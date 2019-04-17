#!groovy

CMAKE_TOOLCHAIN_FILE_ARM="cmake/qnx-ntoarmv7le.cmake"
QNX_SYSROOT_ARM="/home/rxm/rxdep-qnx/sysroots/arm-unknown-nto-qnx6.6.0eabi"
ARM_BUILD_TYPE = 'Release'
RXDEP_QNX_ARM="${QNX_SYSROOT_ARM}/usr/local"


def build(PLATFORMS){
    echo "--- qnx-arm RxMachine ---"

    // pull dokcer image from dockerhub
    def myimage_qnx_arm = docker.image("${env.DOCKERNAME}");
    docker.withRegistry('https://registry.hub.docker.com', 'reflexion-docker-registry-login') {
        myimage_qnx_arm.pull();
        // workaround of the bug to get correct newly pulled image name
        // https://issues.jenkins-ci.org/browse/JENKINS-34276
        myimage_qnx_arm = docker.image(myimage_qnx_arm.imageName());
    }

    def SRC_DIR = "${workspace}/RxMachine"
    def utils_qnxarm = load "${SRC_DIR}/jenkins/utils.groovy"
    def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
    def buildSerialNodes = [RxBaseNode,HWLib]
    def buildNodesarm = [RfNode,DeliveryNode,MonitorNode,SafeOpNode,DataAnalysisAppsRecordProcessor,DataAnalysisAppsOfflineDoseCal,DeployUtils,TestMultipleNodes]
    def version = load "${workspace}/utilities/jenkins/version.groovy"
    def localversion = version.getReleaseNumber()

    PROTOC_PATH = env.RXDEP_NATIVE + "/bin"
    RXDEP_LIBRARY_PATH = env.RXDEP_NATIVE + "/lib"
    if(utils_qnxarm.isBuildForPullRequest()) {
        echo "\u2692 Starting build for pull request: #${env.CHANGE_ID}"
        echo "\u2692 Pull request url: ${env.CHANGE_URL}"
        echo "\u2692 BUILD_URL=${env.BUILD_URL}"
        echo "\u2692 BUILD_ID=${env.BUILD_ID}"
    } else {
        echo "\u2692 Starting build for branch: BRANCH_NAME=${env.BRANCH_NAME}"
        echo "\u2692 BUILD_URL=${env.BUILD_URL}"
        echo "\u2692 BUILD_ID=${env.BUILD_ID}"
    }
    //'QNX_ARM'
    stage('QNX_ARM-Build-Init') {
        myimage_qnx_arm.inside {
            echo "Preparing workspace for build-${PLATFORMS.QNX_arm} ..."
            def shellScript = """
                set +x
                env
                echo ${QNX_ENV_VARS}
                cd ${SRC_DIR}
                export PATH=${PATH}:${PROTOC_PATH}
                export LD_LIBRARY_PATH=${RXDEP_LIBRARY_PATH}
                ./compile_proto.sh
                export LD_LIBRARY_PATH=${RXDEP_QNX_ARM}/lib
                mkdir -p build-${PLATFORMS.QNX_arm}
                cd build-${PLATFORMS.QNX_arm}
            """
            shellScript += utils_qnxarm.sourceWorkaround(QNX_ENV_VARS)
            shellScript += """
                cmake -DCMAKE_PREFIX_PATH=${RXDEP_QNX_ARM} -DCMAKE_TOOLCHAIN_FILE=${SRC_DIR}/${CMAKE_TOOLCHAIN_FILE_ARM} -DQNX_SYSROOT=${QNX_SYSROOT_ARM} -DFPGA_RELEASES=${env.FPGA_RELEASES} -DCMAKE_BUILD_TYPE=${ARM_BUILD_TYPE} ${SRC_DIR} -DGIT_BRANCH=${env.BRANCH_NAME} -DBUILD_NUMBER=${localversion} -DGIT_COMMIT=${env.GIT_COMMIT} -DGIT_CURRENT_STATE=CLEAN  > ${PLATFORMS.QNX_arm}.log
                """
            // sh shellScript
            jenkinsHelper.mysh(shellScript)
        }
    }

    stage('QNX-arm-serial-nodes') {
        try {
               utils_qnxarm.buildNode(PLATFORMS.QNX_arm, myimage_qnx_arm, buildSerialNodes, SRC_DIR, utils_qnxarm, RXDEP_QNX_ARM)
            }
        catch (exc) {
            echo "exception from QNX-arm-serial-nodes Look at *.err file"
            utils_qnxarm.archiveErrorArtifacts(PLATFORMS.QNX_arm, buildSerialNodes, SRC_DIR)
            currentBuild.result = 'FAILURE'
            throw exc
        }
    }

    stage('QNX-arm-build-nodes') {
        try{

            parallel utils_qnxarm.initBuildTasks(PLATFORMS.QNX_arm, myimage_qnx_arm,buildNodesarm,SRC_DIR,utils_qnxarm,RXDEP_QNX_ARM)
        }
        catch(exc){
            echo "From stage QNX_ARM-Build-Nodes exception block. Look at {node}.err file."
            utils_qnxarm.archiveErrorArtifacts(PLATFORMS.QNX_arm,buildNodesarm,SRC_DIR)
            currentBuild.result='FAILURE'
            throw exc
        }

    }


    stage('D:QNX-ARM-Archive-Artifacts') {
        utils_qnxarm.arcArtifacts(PLATFORMS.QNX_arm,buildNodesarm,SRC_DIR)
    }

    ///END QNX_ARM
}
return this
