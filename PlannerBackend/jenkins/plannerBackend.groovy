#!groovy

//*****************Node Configuration for planner backend *************

Algorithms = [
    label: 'Algorithms',
    path : 'Algorithms',
    bin  : 'libRxAlgorithms.a',
    test : [
        path: 'Algorithms/UnitTest',
        bin : 'TestAlgorithms'
    ]
]

RxBaseNode = [
    label: 'RxBaseNode',
    path : 'RxBaseNode',
    test : [
        path   : 'RxBaseNode/UnitTest',
        bin    : 'RxBaseNodeUnitTest',
        exclude: '~"Test Timer thread"'
    ]
]


def build() {
    TOOLS_PATH = "/usr/local/MATLAB/R2017b/bin"
    MATLAB_BIN = "/usr/local/MATLAB/R2017b/bin/matlab"
    DEPLOYTOOL_BIN = "/usr/local/MATLAB/R2017b/bin/deploytool"
    DROPBOX_ROOT = "/home/tmp"

    // DB: will execute the testCases with 'MASTER' tag + testCases with no tag
    MASTER_BRANCH_TEST_TAGS = "{'MASTER'}"
    // DB: will execute the testCases with 'PR' tag + testCases with no tag
    PULL_REQUEST_TEST_TAGS = "{'PR'}"
    // Will execute longer running testCases with 'NIGHTLY' tag
    NIGHTLY_TEST_TAGS = "{'NIGHTLY'}"

    EACH_COMMIT_TEST_TAGS = "{}" // DB: if empty will exeute testCases with no tags

    RX_FILES_ROOT = '~/cases/test' // DB: rxFiles generated during unit tests
    MONGO_DB_NAME = "reflexion-unit-tests" // DB: dedicated mongoDb for unit tests

    PROTOC_PATH = env.RXDEP_NATIVE + "/bin"
    FPGA_RELEASE_PATH = "/home/workspace/FPGA_releases"
    SRC_DIR = "";
    BUILD_TYPE = 'Release'

    // DB: edit the arrays below to configure the ci job steps
    buildNodes = []
    buildNodes = [RxBaseNode, Algorithms]
    archiveArtifacts = buildNodes;

    echo "--- PlannerBackend ---"

    // pull dokcer image from dockerhub
    def myimage = docker.image("${env.DOCKERNAME}");
    docker.withRegistry('https://registry.hub.docker.com', 'reflexion-docker-registry-login') {
        myimage.pull();
        // workaround of the bug to get correct newly pulled image name
        // https://issues.jenkins-ci.org/browse/JENKINS-34276
        myimage = docker.image(myimage.imageName());
    }

    def SRC_DIR = "${workspace}/PlannerBackend";
    jenkinsHelper = load "${workspace}/utilities/jenkins/jenkinsHelper.groovy"
    def version = load "${workspace}/utilities/jenkins/version.groovy"
    def localversion = version.getReleaseNumber()

    stage('Init git submodules') {
        def shellScript = """
                set +x
                git submodule init
                git submodule update
            """
        jenkinsHelper.mysh(shellScript)
    }
    stage('Build MATLAB dependencies') {
        // get test data from S3
        jenkinsHelper.downloadS3("dicom-test-data", "${workspace}/dicom-test-data", 1)
        def unzipScript = """
            cd ${workspace}/dicom-test-data
            for f in `ls *.zip`; do
                unzip \$f
            done
            ls
        """
        jenkinsHelper.mysh(unzipScript)

        myimage.inside('--user=root --mac-address="06:d0:8d:3c:fd:e8"') {
            CMAKE_PREFIX_PATH = RXDEP_NATIVE
            def shellScript = """
                    set -x
                    export PATH=${PATH}:${TOOLS_PATH}
                    export LD_LIBRARY_PATH=${CMAKE_PREFIX_PATH}/lib
                    cd ${SRC_DIR}
                    cd product/zeromq
                    mex -I$CMAKE_PREFIX_PATH/include -L$CMAKE_PREFIX_PATH/lib -lzmq zmq.cc | tee -a $SRC_DIR/RxPlannerBackend.log
                    cd ${SRC_DIR}
                    cd product/cccs
                    mex -O cckRotLine3.cc
                    mex -O cckBeamletsCalc.cc
                    cd ${SRC_DIR}
                    cd product/gamma
                    mex -O -I$SRC_DIR/product/gamma gammaGeom.cpp calGammaFn.cpp volumediff.cpp | tee -a $SRC_DIR/RxPlannerBackend.log
                    cd ${SRC_DIR}
                    matlab -nodesktop  -r "addpath(genpath('./product/'));savepath;setup_dicom;exit" | tee -a $SRC_DIR/RxPlannerBackend.log
                    xvfb-run -a ${DEPLOYTOOL_BIN} -build ${SRC_DIR}/product/mcc/libMxPlannerBackend.prj | tee -a $SRC_DIR/RxPlannerBackend.log
                """
            jenkinsHelper.mysh(shellScript)
        }
        def fExists= fileExists "${SRC_DIR}/product/mcc/libMxPlannerBackend/for_testing/libMxPlannerBackend.so"
        if(!fExists){
            error "libMxPlannerBackend.so not found at  ${SRC_DIR}/product/mcc/libMxPlannerBackend.so "
        }
        else{
            echo "libMxPlannerBackend.so exists "
        }

    }

    stage('Run MATLAB UnitTests') {
        if(!jenkinsHelper.executeUnitTest()){
            echo "Skipping UnitTests PlannerBackend ..."
            return true
        }
        def testTags;
        if (jenkinsHelper.isBuildForPullRequest()) {
            echo "\u2692 Running UnitTests for pull request: #${env.CHANGE_ID}"
            echo "\u2692 Pull request url: ${env.CHANGE_URL}"
            echo "\u2692 BUILD_URL=${env.BUILD_URL}"
            echo "\u2692 BUILD_ID=${env.BUILD_ID}"
            testTags = PULL_REQUEST_TEST_TAGS
        } else if (jenkinsHelper.isBuildForMasterBranch()) {
            echo "\u2692 Running UnitTests for master branch: #${env.CHANGE_ID}"
            echo "\u2692 BUILD_URL=${env.BUILD_URL}"
            echo "\u2692 BUILD_ID=${env.BUILD_ID}"
            testTags = MASTER_BRANCH_TEST_TAGS
        } else if (jenkinsHelper.isBuildForNightlyTest()) {
            echo "\u2692 Running UnitTests for nightly test: #${env.CHANGE_ID}"
            echo "\u2692 BUILD_URL=${env.BUILD_URL}"
            echo "\u2692 BUILD_ID=${env.BUILD_ID}"
            testTags = NIGHTLY_TEST_TAGS
        } else {
            echo "\u2692 Running UnitTests for ${env.BRANCH_NAME} branch: #${env.CHANGE_ID}"
            echo "\u2692 BUILD_URL=${env.BUILD_URL}"
            echo "\u2692 BUILD_ID=${env.BUILD_ID}"
            testTags = EACH_COMMIT_TEST_TAGS
        }
        // construct the script to kickoff the unitTests
        def runUnitTests = $/ "try; \
                    restoredefaultpath; \
                    savepath; \
                    addpath(genpath('${SRC_DIR}/product/')); \
                    setup_dicom; \
                    result = runUnitTests('${SRC_DIR}/', ${testTags}); \
                    disp(table(result)); \
                catch Ex; fprintf(2, Ex.getReport()); quit(1); end; \
                quit(0);"
            /$

        myimage.inside('--user=root --mac-address="06:d0:8d:3c:fd:e8"') {
            sh """
                cd ${workspace}
                ${MATLAB_BIN} -nodisplay -r ${runUnitTests} >> $SRC_DIR/RxPlannerBackend.log
                cd ${workspace}/utilities/codecov
                chmod a+x uploadcodecov.sh
                cd ${workspace}
                ${workspace}/utilities/codecov/uploadcodecov.sh
            """
        }
        archiveArtifacts allowEmptyArchive: true, artifacts: 'PlannerBackend/product/nightlyTest/*.json', fingerprint: true
    }

    stage('Publish Test Results') {
        if(!jenkinsHelper.executeUnitTest()){
            echo "Nothing to publish as skipping UnitTests flag is on, PlannerBackend ..."
            return true
        }
        step(
            [$class     : 'JUnitResultArchiver',
             testResults: "**/PlannerBackend/results.xml"]
        )
    }

    stage('pb:linux-build-init') {
        myimage.inside('--user=root --mac-address="06:d0:8d:3c:fd:e8"') {
            CMAKE_PREFIX_PATH = RXDEP_NATIVE
            def shellScript = """
                    set +x
                    cd ${workspace}/RxMachine
                    #export PATH=${PATH}:${PROTOC_PATH}
                    #./compile_proto.sh
                    export LD_LIBRARY_PATH=${CMAKE_PREFIX_PATH}/lib
                    #env
                    cmake -DCMAKE_PREFIX_PATH=${CMAKE_PREFIX_PATH} -DCMAKE_BUILD_TYPE=${BUILD_TYPE} -DFPGA_RELEASES=${FPGA_RELEASE_PATH} -DGIT_BRANCH=${env.BRANCH_NAME} -DBUILD_NUMBER=${localversion} -DGIT_COMMIT=${env.GIT_COMMIT} -DGIT_CURRENT_STATE=CLEAN ${workspace}/RxMachine >> $SRC_DIR/RxPlannerBackend.log
                """
            jenkinsHelper.mysh(shellScript)
        }
    }

    stage('pb:linux-build-nodes') {
        try {
            myimage.inside('--user=root --mac-address="06:d0:8d:3c:fd:e8"') {
                CMAKE_PREFIX_PATH = RXDEP_NATIVE
                def idx = buildNodes.size();
                for (int i = 0; i < idx; i++) {
                    def node = buildNodes[i];
                    def shellScript = """
                            set +x
                            export LD_LIBRARY_PATH=${CMAKE_PREFIX_PATH}/lib
                            echo "Starting ${node.label}"
                            cd ${workspace}/RxMachine/${node.path}
                            make -j8 >> $SRC_DIR/RxPlannerBackend.log
                        """
                    jenkinsHelper.mysh(shellScript)

                }
            }
        }
        catch (exc) {
            echo "exception from pb:Ubuntu-Build-Nodes. Look at RxPlannerBackend.log file"
            archiveArtifacts allowEmptyArchive: true, artifacts: '**/PlannerBackend/*.log', fingerprint: true
            currentBuild.result = 'FAILURE'
            throw exc
        }
    }

    stage('Build PlannerBackend') {
        try {
            myimage.inside('--user=root --mac-address="06:d0:8d:3c:fd:e8"') {
                CMAKE_PREFIX_PATH = RXDEP_NATIVE
                def shellScript = """
                        export LD_LIBRARY_PATH=${CMAKE_PREFIX_PATH}/lib
                        export PATH=${PATH}:${TOOLS_PATH}
                        cd ${SRC_DIR}
                        echo ${workspace}
                        export RXCOMMON_PATH=${workspace}/RxMachine
                        cmake -DCMAKE_PREFIX_PATH=${CMAKE_PREFIX_PATH} -DCMAKE_BUILD_TYPE=${BUILD_TYPE} -DFPGA_RELEASES=${FPGA_RELEASE_PATH} -DGIT_BRANCH=${env.BRANCH_NAME} -DBUILD_NUMBER=${localversion} -DGIT_COMMIT=${env.GIT_COMMIT} -DGIT_CURRENT_STATE=CLEAN ${SRC_DIR} >> $SRC_DIR/RxPlannerBackend.log
                        make -j8 >> $SRC_DIR/RxPlannerBackend.log
                        ls -al ${SRC_DIR}/PlannerBackendNode
                        patchelf --set-rpath '\$ORIGIN:\$ORIGIN/lib:/usr/local/MATLAB/MATLAB_Runtime/v93/runtime/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/bin/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/sys/os/glnxa64:/home/rxm/lib:/usr/local/MATLAB/R2017b/runtime/glnxa64:/usr/local/MATLAB/R2017b/bin/glnxa64:/usr/local/MATLAB/R2017b/sys/os/glnxa64' ${SRC_DIR}/PlannerBackendNode/PlannerBackendNode
                        patchelf --set-rpath '\$ORIGIN:\$ORIGIN/lib:/usr/local/MATLAB/MATLAB_Runtime/v93/runtime/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/bin/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/sys/os/glnxa64:/home/rxm/lib:/usr/local/MATLAB/R2017b/runtime/glnxa64:/usr/local/MATLAB/R2017b/bin/glnxa64:/usr/local/MATLAB/R2017b/sys/os/glnxa64' ${SRC_DIR}/DeliveryBackendNode/DeliveryBackendNode
                        patchelf --set-rpath '\$ORIGIN:\$ORIGIN/lib:/usr/local/MATLAB/MATLAB_Runtime/v93/runtime/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/bin/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/sys/os/glnxa64:/home/rxm/lib:/usr/local/MATLAB/R2017b/runtime/glnxa64:/usr/local/MATLAB/R2017b/bin/glnxa64:/usr/local/MATLAB/R2017b/sys/os/glnxa64' ${SRC_DIR}/tools/DoseAccuracy
                        patchelf --set-rpath '\$ORIGIN:\$ORIGIN/lib:/usr/local/MATLAB/MATLAB_Runtime/v93/runtime/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/bin/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/sys/os/glnxa64:/home/rxm/lib:/usr/local/MATLAB/R2017b/runtime/glnxa64:/usr/local/MATLAB/R2017b/bin/glnxa64:/usr/local/MATLAB/R2017b/sys/os/glnxa64' ${SRC_DIR}/PlannerBackendNode/libMxPlannerBackend.so
                        cp ${SRC_DIR}/product/dicom-rx.txt ${SRC_DIR}/PlannerBackendNode/
                    """
                jenkinsHelper.mysh(shellScript)
            }
        }
        catch (exc) {
            echo "exception from Build PlannerBackend. Look at RxPlannerBackend.log file"
            archiveArtifacts allowEmptyArchive: true, artifacts: '**/PlannerBackend/*.log', fingerprint: true
            currentBuild.result = 'FAILURE'
            throw exc
        }
    }

    if( env.BRANCH_NAME == "stable-master")
    {
        stage('Download nightly test data from Amazon S3'){
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

        stage('Execute DeliveryBackend unittests') {
            try {
                myimage.inside('--user=root --mac-address="06:d0:8d:3c:fd:e8"') {
                    CMAKE_PREFIX_PATH = RXDEP_NATIVE
                    def shellScript = """
                            set -x
                            export PATH=${PATH}:${TOOLS_PATH}
                            export LD_LIBRARY_PATH=${CMAKE_PREFIX_PATH}/lib
                            cd ${workspace}/utilities/machinedata-tools/
                            npm install
                            chmod +x merge-machinedata-kvct-playback-with-pet-jenkins.sh
                            ./merge-machinedata-kvct-playback-with-pet-jenkins.sh
                            cd ${workspace}/PlannerBackend/DeliveryBackendNode
                            export NODE_CONFIG_DIR=${workspace}/SimData/merged-pet
                            ./DeliveryBackendNode -p true
                        """
                    jenkinsHelper.mysh(shellScript)
                }
            }
            catch (exc) {
                echo "exception from Execute DeliveryBackend unittest"
            }
        }

        stage('Generate Delivery Backend UnitTest xml') {
            try {
                myimage.inside('--user=root --mac-address="06:d0:8d:3c:fd:e8"') {
                    def shellScript = """
                            cd ${workspace}/PlannerBackend/DeliveryBackendNode/UnitTest
                            make
                            echo Executing Unittest
                            ./TestDeliveryBackend -r junit | tee TestDeliveryBackend.xml
                        """
                    jenkinsHelper.mysh(shellScript)
                }
                def artifactsStr = "**/DeliveryBackendNode/UnitTest/TestDeliveryBackend.xml"
                echo "Attempting to archive artifacts ${artifactsStr} ..."

                step(
                    [$class   : 'ArtifactArchiver',
                    artifacts: artifactsStr, fingerprint: true, allowEmptyArchive: true]
                )
            }
            catch (exc) {
                echo "exception from Execute DeliveryBackend unittest"
            }
        }
    }

    stage('Matlab Static code analysis') {
        try {
            myimage.inside('--user=root --mac-address="06:d0:8d:3c:fd:e8"') {
                CMAKE_PREFIX_PATH = RXDEP_NATIVE
                def shellScript = """
                        set -x
                        export PATH=${PATH}:${TOOLS_PATH}
                        export LD_LIBRARY_PATH=${CMAKE_PREFIX_PATH}/lib
                        cd ${workspace}/PlannerBackend/research/scripts
                        mv ${workspace}/dicom-test-data/StaticCode-Old-Errors.csv ${workspace}/PlannerBackend/product/
                        matlab -nodesktop -r "try;staticCode('StaticCode-Old-Errors.csv');catch Ex;fprintf(2, Ex.getReport());quit(1);end;quit(0);"
                        cd ${workspace}
                    """
                jenkinsHelper.mysh(shellScript)
                archiveArtifacts allowEmptyArchive: true, artifacts: '**/PlannerBackend/product/StaticCode-Old-Errors.csv', fingerprint: true
            }
        }
        catch (exc) {
            archiveArtifacts allowEmptyArchive: true, artifacts: '**/PlannerBackend/product/StaticCode-Old-Errors.csv', fingerprint: true
            archiveArtifacts allowEmptyArchive: true, artifacts: '**/PlannerBackend/product/StaticCode-New-Errors.csv', fingerprint: true
            echo "exception from Matlab Static code analysis"
            echo "Error!!! Checkcode detected new warnings, Please fix the warning messages. Refer StaticCode-New-Errors.csv stored in others build-artifact"
            currentBuild.result = 'FAILURE'
            throw exc
        }
    }

    stage('Archive artifacts') {
        def artifactsStr = "**/product/mcc/libMxPlannerBackend"
        artifactsStr += ", **/PlannerBackendNode/PlannerBackendNode"
        artifactsStr += ", **/PlannerBackendNode/libMxPlannerBackend.so"
        artifactsStr += ", **/PlannerBackendNode/dicom-rx.txt"
        artifactsStr += ", **/DeliveryBackendNode/DeliveryBackendNode"
        artifactsStr += ", **/PlannerBackend/*.log"
        artifactsStr += ", **/tools/DoseAccuracy"
        artifactsStr += ", **/DeliveryBackendNode/dicom-rx.txt"

        echo "Attempting to archive artifacts ${artifactsStr} ..."

        step(
            [$class   : 'ArtifactArchiver',
             artifacts: artifactsStr, fingerprint: true]
        )
    }

}

return this
