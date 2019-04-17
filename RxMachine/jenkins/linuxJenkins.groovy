#!groovy

def build(PLATFORMS) {
    UBUNTU_BUILD_TYPE = 'Release'
    RXDEP_NATIVE = env.RXDEP_NATIVE
    PROTOC_PATH = env.RXDEP_NATIVE + "/bin"
    RXDEP_LIBRARY_PATH = env.RXDEP_NATIVE + "/lib"
    // pull docker image from dockerhub
    def myimageUbuntu = docker.image("${env.DOCKERNAME}");
    docker.withRegistry('https://registry.hub.docker.com', 'reflexion-docker-registry-login') {
        myimageUbuntu.pull();
        // workaround of the bug to get correct newly pulled image name
        // https://issues.jenkins-ci.org/browse/JENKINS-34276
        myimageUbuntu = docker.image(myimageUbuntu.imageName());
    }

    def SRC_DIR = "${workspace}/RxMachine"
    def utils_ubuntu = load "${SRC_DIR}/jenkins/utils.groovy"
    def jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
    def version = load "${workspace}/utilities/jenkins/version.groovy"
    def localversion = version.getReleaseNumber()

    def buildSerialNodes = [RxBaseNode, CommonLib, HWLib, Algorithms]

    //DataAnalysisAppsRecordProcessor removed as race condition with CouchNode
    def buildNodesUbuntu = [DeliveryNode, SysNode, RfNode, KVCTNode, DosimetryNode, GantryNode, CoolingRingNode, CoolingStandNode, CouchNode, MixNode, Egrt, PetNode, LogNode, DeployUtils, TestMultipleNodes]
    
    if (utils_ubuntu.isBuildForPullRequest()) {
        echo "\u2692 Starting build for pull request: #${env.CHANGE_ID}"
        echo "\u2692 Pull request url: ${env.CHANGE_URL}"
        echo "\u2692 BUILD_URL=${env.BUILD_URL}"
        echo "\u2692 BUILD_ID=${env.BUILD_ID}"
    } else {
        echo "\u2692 Starting build for branch: BRANCH_NAME=${env.BRANCH_NAME}"
        echo "\u2692 BUILD_URL=${env.BUILD_URL}"
        echo "\u2692 BUILD_ID=${env.BUILD_ID}"
    }
    //UBUNTU_x86
    stage('Init git submodules') {
        def shellScript = """
                set +x
                git submodule init
                git submodule update
            """
        jenkinsHelper.mysh(shellScript)
    }

    stage('linux-build-init') {
        myimageUbuntu.inside {
            echo "Preparing workspace for build-${PLATFORMS.UBUNTU_x86} ..."
            def shellScript = """
                set +x
                cd ${SRC_DIR}
                export PATH=${PATH}:${PROTOC_PATH}
                export LD_LIBRARY_PATH=${RXDEP_LIBRARY_PATH}
                ./compile_proto.sh
                mkdir -p build-${PLATFORMS.UBUNTU_x86}
                cd build-${PLATFORMS.UBUNTU_x86}
                cmake -DCMAKE_PREFIX_PATH=${RXDEP_NATIVE} -DFPGA_RELEASES=${env.FPGA_RELEASES} -DCMAKE_BUILD_TYPE=${UBUNTU_BUILD_TYPE} ${SRC_DIR} -DGIT_BRANCH=${env.BRANCH_NAME} -DBUILD_NUMBER=${localversion} -DGIT_COMMIT=${env.GIT_COMMIT} -DGIT_CURRENT_STATE=CLEAN > ${PLATFORMS.UBUNTU_x86}.log
                """
            jenkinsHelper.mysh(shellScript)
        }
    }

    stage('linux-serial-nodes') {
        try {
               utils_ubuntu.buildNode(PLATFORMS.UBUNTU_x86, myimageUbuntu, buildSerialNodes, SRC_DIR, utils_ubuntu, RXDEP_NATIVE)
            }
        catch (exc) {
            echo "exception from linux-serial-nodes. Look at *.err file"
            utils_ubuntu.archiveErrorArtifacts(PLATFORMS.UBUNTU_x86, buildSerialNodes, SRC_DIR)
            currentBuild.result = 'FAILURE'
            throw exc
        }
    }

    stage('linux-parallel-Nodes') {
        try {
            parallel utils_ubuntu.initBuildTasks(PLATFORMS.UBUNTU_x86, myimageUbuntu, buildNodesUbuntu, SRC_DIR, utils_ubuntu, RXDEP_NATIVE)
        }
        catch (exc) {
            echo "exception from Ubuntu-Build-Nodes. Look at failed {node}.err file"
            utils_ubuntu.archiveErrorArtifacts(PLATFORMS.UBUNTU_x86, buildNodesUbuntu, SRC_DIR)
            currentBuild.result = 'FAILURE'
            throw exc
        }
    }

    stage('linux-Build-DataAnalysisApp') {
        try {
            utils_ubuntu.buildDataAnalysisApp(PLATFORMS.UBUNTU_x86, myimageUbuntu, buildNodesUbuntu, SRC_DIR, utils_ubuntu, RXDEP_NATIVE)
        }
        catch (exc) {
            echo "exception from Ubuntu-Build-DataAnalysisApp. Look at DataAnalysisApp.err file"
            utils_ubuntu.archiveErrorArtifacts(PLATFORMS.UBUNTU_x86, buildNodesUbuntu, SRC_DIR)
            currentBuild.result = 'FAILURE'
            throw exc
        }
    }

    stage('D:linux-Archive-Artifacts') {
        utils_ubuntu.arcArtifacts(PLATFORMS.UBUNTU_x86, buildNodesUbuntu, SRC_DIR)
    }

    stage('D:linux-Test-Nodes') {
        if(!jenkinsHelper.executeUnitTest()){
            echo "Skipping UnitTests For UBUNTU_x86 ..."
            return true
        }
        parallel utils_ubuntu.initTestTasks(PLATFORMS.UBUNTU_x86, myimageUbuntu, SRC_DIR)
    }
    
    stage('D:execute-Unittest-Algorithms') {
        if(!jenkinsHelper.executeUnitTest()){
            echo "Skipping UnitTests For Algorithms ..."
            return true
        }
        
        try {
            utils_ubuntu.executeAlgorithms(PLATFORMS.UBUNTU_x86, myimageUbuntu, SRC_DIR)
        }
        catch (exc) {
            echo "exception from Algorithms Unittest execution"
            utils_ubuntu.archiveErrorArtifacts(PLATFORMS.UBUNTU_x86, buildNodesUbuntu, SRC_DIR)
            currentBuild.result = 'UNSTABLE'
            throw exc
        }
    }

    if( env.BRANCH_NAME == "stable-master")
    {
        stage('D:Coverage-Algorithms') {
            try {
                myimageUbuntu.inside('--user root') {
                echo "Preparing workspace for build-${PLATFORMS.UBUNTU_x86} ..."
                def shellScript = """
                    set +x
                    cd ${workspace}/utilities/codecov
                    chmod a+x uploadcodecov.sh
                    cd ${SRC_DIR}
                    export LD_LIBRARY_PATH=${RXDEP_LIBRARY_PATH}
                    cd build-${PLATFORMS.UBUNTU_x86}
                    apt-get update
                    apt-get install lcov -y --fix-missing
                    cmake .. -DCMAKE_PREFIX_PATH=${RXDEP_NATIVE} -DCMAKE_BUILD_TYPE=Coverage
                    cd ${workspace}/RxMachine/build-${PLATFORMS.UBUNTU_x86}/${Algorithms.path}
                    make -j4 > ${Algorithms.label}.log 2> ${Algorithms.label}.err
                    cd ${workspace}/RxMachine/build-${PLATFORMS.UBUNTU_x86}/${Algorithms.test.path}
                    export RXDB_DIR=${workspace}/RxDB/unittest/phantom-point/
                    make ${Algorithms.test.bin}_coverage
                    cd ${workspace}/RxMachine/build-${PLATFORMS.UBUNTU_x86}
                    lcov --summary Coverage.info
                    zip -r Coverage.zip Coverage
                    ${workspace}/utilities/codecov/uploadcodecov.sh
                    """
                jenkinsHelper.mysh(shellScript)
                }
                utils_ubuntu.archiveCoverageArtifacts(PLATFORMS.UBUNTU_x86, Algorithms, SRC_DIR)
            }    
            catch (exc) {
                echo "exception from Coverage-Algorithms. Look at *.err file"
                utils_ubuntu.archiveErrorArtifacts(PLATFORMS.UBUNTU_x86, Algorithms, SRC_DIR)
                currentBuild.result = 'UNSTABLE'
            }
        }

        stage('D: Downloading nightly test data')
        {
            // get test data from S3
            jenkinsHelper.downloadS3("stable-master-test-data", "${workspace}/stable-master-test-data", 1)
            def unzipScript = """
                cd ${workspace}/stable-master-test-data
                for f in `ls *.zip`; do
                    unzip \$f
                done
                ls
            """
            jenkinsHelper.mysh(unzipScript)
        }
        
        stage('D:Execute-Egrt') {
            try{
                utils_ubuntu.executeEgrt(PLATFORMS.UBUNTU_x86, myimageUbuntu, SRC_DIR)
            }
            catch (exc) {
                echo "exception from Execute Egrt. Look at DataAnalysisApp.err file"
                utils_ubuntu.archiveErrorArtifacts(PLATFORMS.UBUNTU_x86, Egrt, SRC_DIR)
                currentBuild.result = 'FAILURE'
                throw exc
            }
        }
    }
    //UBUNTU_x86
}

return this