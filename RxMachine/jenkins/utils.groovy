#!groovy

//**************** Node Configuration ******************************************
// paths are relative to the build directory
RxBaseNode = [
    label: 'RxBaseNode',
    path : 'RxBaseNode',
    test : [
        path   : 'RxBaseNode/UnitTest',
        bin    : 'RxBaseNodeUnitTest',
        exclude: '~"Test Timer thread"'
    ]
]

SafeOpNode = [
    label: 'SafeOpNode',
    path : 'SafeOpNode',
    bin  : 'SafeOpNode'
]

MonitorNode = [
    label: 'MonitorNode',
    path : 'MonitorNode',
    bin  : 'MonitorNode'
]

CommonLib = [
    label: 'CommonLib',
    path : 'CommonLib',
    bin  : 'CommonLib'
]
HWLib = [
    label: 'HWLib',
    path : 'HWLib',
    bin  : 'HWLib'
]
DeliveryNode = [
    label: 'DeliveryNode',
    path : 'DeliveryNode',
    bin  : 'DeliveryNode',
    test : [
        path: 'DeliveryNode/UnitTest',
        bin : 'DeliveryNodeUnitTest'
    ]
]

SysNode = [
    label: 'SysNode',
    path : 'SysNode',
    bin  : 'SysNode'
    // test: [
    //     path: '/KVCTNode/UnitTest',
    //     bin: 'TestKVCTNode'
    // ]
]

KVCTNode = [
    label: 'KVCTNode',
    path : 'KVCTNode',
    bin  : 'KVCTNode',
    test : [
        path: 'KVCTNode/UnitTest',
        bin : 'TestKVCTNode'
    ]
]

DosimetryNode = [
    label: 'DosimetryNode',
    path : 'DosimetryNode',
    bin  : 'DosimetryNode',
    test : [
        path: 'DosimetryNode/UnitTest',
        bin : 'TestDosimetryNode'
    ]
]
MixNode = [
    label: 'MixNode',
    path : 'MixNode',
    bin  : 'MixNode',
    test : [
        path: 'MixNode/UnitTest',
        bin : 'TestMixNode'
    ]
]

RfNode = [
    label: 'RfNode',
    path : 'RfNode',
    bin  : 'RfNode',
    test : [
        path: 'RfNode/UnitTest',
        bin : 'TestRfNode'
    ]
]

GantryNode = [
    label: 'GantryNode',
    path : 'GantryNode',
    bin  : 'GantryNode'
    // test: [
    //     path: '/KVCTNode/UnitTest',
    //     bin: 'TestKVCTNode'
    // ]
]

CoolingRingNode = [
    label: 'CoolingRingNode',
    path : 'CoolingRingNode',
    bin  : 'CoolingRingNode',
    test : [
        path: 'CoolingRingNode/UnitTest',
        bin : 'TestCoolingRingNode'
    ]
]

CoolingStandNode = [
    label: 'CoolingStandNode',
    path : 'CoolingStandNode',
    bin  : 'CoolingStandNode',
    test : [
        path: 'CoolingStandNode/UnitTest',
        bin : 'TestCoolingStandNode'
    ]
]

CouchNode = [
    label: 'CouchNode',
    path : 'CouchNode',
    bin  : 'CouchNode',
    test : [
        path: 'CouchNode/UnitTest',
        bin : 'TestCouchNode'
    ]
]

Egrt = [
    label: 'Egrt',
    path : 'Egrt/EgrtNode',
    bin  : 'EgrtNode',
    test : [
        path: 'Egrt/EgrtNode',
        bin : 'EgrtNode'
    ]
]

PetNode = [
    label: 'PetNode',
    path : 'PetNode',
    bin  : 'PetNode'
    // test: [
    //     path: 'PetNode/UnitTest',
    //     bin: 'TestCouchNode'
    // ]
]

LogNode = [
    label: 'LogNode',
    path : 'LogNode',
    bin  : 'LogNode'
]

DataAnalysisAppsRecordProcessor = [
    label: 'RecordProcessor',
    path : 'DataAnalysisApps/RecordProcessor',
    bin  : 'RecordProcessor'
]

DataAnalysisAppsOfflineDoseCal = [
    label: 'OfflineDoseCal',
    path : 'DataAnalysisApps/OfflineDoseCal',
    bin  : 'OfflineDoseCal'
]
DeployUtils = [
    label: 'DeployUtils',
    path : 'DeployUtils',
    bin  : 'installnodes'
]
TestMultipleNodes = [
    label: 'TestMultipleNodes',
    path : 'TestMultipleNodes',
    bin  : 'TestMultipleNodes'
]
Algorithms = [
    label: 'Algorithms',
    path : 'Algorithms',
    bin  : 'libRxAlgorithms.a',
    test : [
        path: 'Algorithms/UnitTest',
        bin : 'TestAlgorithms'
    ]
]

testNodes = [RxBaseNode];
//**************** End of Node Configuration ***********************************

/**
 * Check if the current build should execute the pipline for pull request
 *
 * @return "true" in case of pull request pipline, otherwise "false"
 */
def isBuildForPullRequest() {
    return env.CHANGE_ID != null
}

/**
 * Workarond to source env vars in Jenkins pipeline
 *  source & (dot) command return exit code 1 and causes the job to fail
 *
 * DB: requires script approvals in groovy sandbox
 *      new java.io.File java.lang.String
 *      staticMethod org.codehaus.groovy.runtime.DefaultGroovyMethods getText java.io.File
 * @return content of the shell script as string
 */
def sourceWorkaround(pathToShellScript) {
    if (env.NODE_LABELS.indexOf('slave') != -1) {
        // running on slave node
        def scriptName = pathToShellScript.split('/')
        scriptName = scriptName[scriptName.length - 1].toString()
        sh """
            cp ${pathToShellScript} ${env.Workspace}/${scriptName}
        """
        return readFile(scriptName)
    } else {
        // running on master node
        return new File(pathToShellScript).text
    }
}

def mysh(cmd) {
    sh('#!/bin/sh -e\n' + cmd)
}

def initBuildTasks(platform, dockerimage, buildNodes, workspace, utils, cmakeprefixpath) {
    def buildTasks = [:]

    def idx = buildNodes.size();
    for (int i = 0; i < idx; i++) {
        def node = buildNodes[i];
        buildTasks["${buildNodes[i].label}"] = {
            echo "Starting ${node.label} build-${platform} ..."
            dockerimage.inside {
                def shellScript = """
                    set +x
                    """
                switch (platform) {
                    case [PLATFORMS.QNX_x86, PLATFORMS.QNX_arm]:
                        shellScript += utils.sourceWorkaround(QNX_ENV_VARS)
                        break
                }
                // DB: This build jobs will run in parallel, so we are using single code build here
                shellScript += """
                    export LD_LIBRARY_PATH=${cmakeprefixpath}/lib
                    cd ${workspace}/build-${platform}/${node.path}
                    make -j4 > ${node.label}.log 2> ${node.label}.err
                """

                if (platform == PLATFORMS.UBUNTU_x86 && node.label != 'Algorithms' && node.label != 'DeployUtils') {
                    shellScript += """
                        patchelf --set-rpath '\$ORIGIN:\$ORIGIN/lib:/home/rxm/rxdep-native/lib:/home/rxm/lib' ${node.bin}
                    """
                }

                mysh(shellScript)

                //Copy unitTest artifact
                if (platform == PLATFORMS.UBUNTU_x86 && node.label == 'KVCTNode') {
                    copyUnitTestexe(platform, node, workspace)
                }
                if (platform == PLATFORMS.QNX_arm && (node.label == 'RfNode' || node.label == 'DeliveryNode')) {
                    copyUnitTestexe(platform, node, workspace)
                }
                if (platform == PLATFORMS.QNX_x86 && (node.label == 'Algorithms' || node.label == 'KVCTNode')) {
                    echo node.label
                    copyUnitTestexe(platform, node, workspace)
                }
            }
        }
    }
    return buildTasks
}

def initTestTasks(platform, dockerimage, workspace) {
    def testTasks = [:]
    for (int i = 0; i < testNodes.size(); i++) {
        def node = testNodes[i]
        testTasks["${node.label}"] = {
            echo "Starting ${node.label} UnitTests-${platform} ..."
                dockerimage.inside {
                    def shellScript = """
                        set +x
                        """
                    shellScript += """
                            cd ${workspace}/build-${platform}/${node.test.path}
                            export WORKSPACE=${workspace}
                            export NODE_CONFIG_DIR=${workspace}/../${NODE_CONFIG_DIR}
                            ./${node.test.bin} ${node.test.exclude} -r junit | tee ${node.test.bin}.xml
                        """
                    mysh(shellScript)
                }
            def artifactsStr = "**/build-${platform}/${node.test.path}/${node.test.bin}.xml"
            echo "Attempting to archive artifacts ${artifactsStr} ..."

            step(
                [$class     : 'JUnitResultArchiver',
                 testResults: "**/build-${platform}/${node.test.path}/${node.test.bin}.xml", allowEmptyResults: true]
            )
            step(
                [$class   : 'ArtifactArchiver',
                artifacts: artifactsStr, fingerprint: true, allowEmptyArchive: true]
            )
        }
    }
    return testTasks
}

def executeAlgorithms(platform, dockerimage, workspace) {
    def node = Algorithms
    echo "Starting ${node.label} UnitTests-${platform} ..."
    dockerimage.inside {
        def shellScript = """
            set +x
            """
        shellScript += """
                export RXDB_DIR=${workspace}/../RxDB/unittest/phantom-point/
                export NODE_CONFIG_DIR=${workspace}/../${NODE_CONFIG_DIR}
                cd ${workspace}/build-${platform}/${node.test.path}
                ./${node.test.bin} -r junit | tee ${node.test.bin}.xml
            """
        mysh(shellScript)
    }
    def artifactsStr = "**/build-${platform}/${node.test.path}/${node.test.bin}.xml"
    echo "Attempting to archive artifacts ${artifactsStr} ..."

    step(
        [$class     : 'JUnitResultArchiver',
            testResults: "**/build-${platform}/${node.test.path}/${node.test.bin}.xml", allowEmptyResults: true]
    )
    step(
        [$class   : 'ArtifactArchiver',
        artifacts: artifactsStr, fingerprint: true, allowEmptyArchive: true]
    )
}

def buildNode(platform, dockerimage, buildNodes, workspace, utils, cmakeprefixpath) {
    dockerimage.inside {
        def idx = buildNodes.size();
        for (int i = 0; i < idx; i++) {
            def node = buildNodes[i];
            echo "Starting ${node.label} build-${platform} ..."
            def shellScript = """
                    set +x
                """
            switch (platform) {
                case [PLATFORMS.QNX_x86, PLATFORMS.QNX_arm]:
                    shellScript += utils.sourceWorkaround(QNX_ENV_VARS)
                    break
            }

            shellScript += """
                    export LD_LIBRARY_PATH=${cmakeprefixpath}/lib
                    cd "${workspace}/build-${platform}/${node.path}"
                    make -j8 > ${node.label}.log 2> ${node.label}.err
                """
            mysh(shellScript)
            if (platform == PLATFORMS.QNX_x86 && node.label == 'Algorithms') {
                copyUnitTestexe(platform, node, workspace)
            }
            if (node.label == 'RxBaseNode') {
                copyUnitTestexe(platform, node, workspace)
            }
        }
    }

}

//Build data analysis app at end separatley as it has dependencies on Couch node.
def buildDataAnalysisApp(platform, dockerimage, buildNodes, workspace, utils, cmakeprefixpath) {
    echo "Starting DataAnalysisApps for build-${platform} ..."
    dockerimage.inside {
        buildNodes.add(DataAnalysisAppsRecordProcessor)
        buildNodes.add(DataAnalysisAppsOfflineDoseCal)

        def shellScript = """
                set +x
                """

        switch (platform) {
            case [PLATFORMS.QNX_x86]:
                shellScript += utils.sourceWorkaround(QNX_ENV_VARS)
                break
        }

        shellScript += """
                export LD_LIBRARY_PATH=${cmakeprefixpath}/lib
                cd ${workspace}/build-${platform}/${DataAnalysisAppsRecordProcessor.path}
                make -j4 > ${DataAnalysisAppsRecordProcessor.label}.log 2> ${DataAnalysisAppsRecordProcessor.label}.err
                cd ${workspace}/build-${platform}/${DataAnalysisAppsOfflineDoseCal.path}
                make -j4 > ${DataAnalysisAppsOfflineDoseCal.label}.log 2> ${DataAnalysisAppsOfflineDoseCal.label}.err
            """
        mysh(shellScript)
    }
}

def arcArtifacts(platform, artifacts, workspace) {
    def artifactsStr = "**/build-${platform}/${platform}.log ,"

    for (int i = 0; i < artifacts.size(); i++) {
        def node = artifacts[i];
        artifactsStr += "**/build-${platform}/${node.path}/${node.bin} ,**/build-${platform}/${node.path}/${node.bin}.log ,"
    }

    //NOTE: Upload configuration file only for QNX_ARM as they are same for QNX_x86 and Ubuntu environment
    if (platform == PLATFORMS.QNX_arm) {
        echo "Copying NodeConfigData from ${workspace}/${NODE_CONFIG_DIR} dir to workspace for archiving ..."
        def nodeConfigData = "${workspace}/../${NODE_CONFIG_DIR}/*.*"
        artifactsStr += "**/MachineData/*.json, **/MachineData/*.csv, **/MachineData/*.proto, **/MachineData/configs/**/*.mc, **/MachineData/RxMachineInfo.proto"
    }

    echo "Attempting to archive artifacts ${artifactsStr} ..."

    step(
        [$class   : 'ArtifactArchiver',
         artifacts: artifactsStr, fingerprint: true, allowEmptyArchive: true]
    )
}

def archiveErrorArtifacts(platform, artifacts, workspace) {
    def artifactsStr = "**/build-${platform}/**/*.err"
    echo "Attempting to archive error artifacts ${artifactsStr} ..."

    step(
        [$class   : 'ArtifactArchiver',
         artifacts: artifactsStr, fingerprint: true, allowEmptyArchive: true]
    )
}

def archiveCoverageArtifacts(platform, artifacts, workspace) {
    def artifactsStr = "**/build-${platform}/*.zip"
    echo "Attempting to archive error artifacts ${artifactsStr} ..."

    step(
        [$class   : 'ArtifactArchiver',
         artifacts: artifactsStr, fingerprint: true, allowEmptyArchive: true]
    )
}

def copyUnitTestexe(platform, node, workspace) {
    def exePath = "${workspace}/build-${platform}/${node.test.path}/${node.test.bin}"
    def artifactsStr = "**/UnitTest-${platform}/*"
    echo "From copyUnitTestexe attempting to archive artifacts ${platform} ..."
    sh """
        set +x
        mkdir -p UnitTest-${platform}
        cd UnitTest-${platform}
        ls -al ${exePath}
        cp ${exePath} .
    """
    step(
        [$class   : 'ArtifactArchiver',
         artifacts: artifactsStr, fingerprint: true, allowEmptyArchive: true]
    )
}

def uploadUnitTestexe(platform) {
    def artifactsStr = "**/UnitTest-${platform}/*"
    echo "From uploadUnitTestexe attempting to archive artifacts ${platform} ..."
    step(
        [$class   : 'ArtifactArchiver',
         artifacts: artifactsStr, fingerprint: true, allowEmptyArchive: true]
    )
}

def mergemachinedatasimuxlab(){
    try{
        // Create a merge data needed for Ubuntu environments
        def shellScript = """
            #Generate machinedata for Linux environments.
            cd ${workspace}/utilities/machinedata-tools/
            npm install
            ./merge-machinedata-sim.sh
            ./merge-machinedata-uxlab.sh
        """
        mysh(shellScript)
    }
    catch(exc){
        echo "exception from merge-machinedata-sim-uxlab."
        currentBuild.result = 'FAILURE'
        throw exc
    }
}

def executeEgrt(platform, dockerimage, workspace) {
    def node = Egrt
    echo "Starting ${node.label} UnitTests-${platform} ..."
    dockerimage.inside('--user root') 
    {
        def shellScript = """
            set +x
            cd ${workspace}/../utilities/machinedata-tools/
            npm install
            chmod +x merge-machinedata-egrt-playback-with-pet-jenkins.sh
            ./merge-machinedata-egrt-playback-with-pet-jenkins.sh
            cd ${workspace}/build-${platform}/${node.test.path}
            export NODE_CONFIG_DIR=${workspace}/../SimData/merged-egrt                           
            ./${node.test.bin} -s true
            cd ../UnitTest/
            make
            ./EgrtUnitTest -r junit | tee EgrtUnitTest.xml
            mv EgrtUnitTest.xml ${workspace}/build-${platform}/
        """
        mysh(shellScript)
    }
    def artifactsStr = "**/build-${platform}/*.xml"
    echo "Attempting to archive artifacts ${artifactsStr} ..."
    step(
        [$class   : 'ArtifactArchiver',
         artifacts: artifactsStr, fingerprint: true, allowEmptyArchive: true]
    )
    echo "End of EGRT Execution"
}

return this
