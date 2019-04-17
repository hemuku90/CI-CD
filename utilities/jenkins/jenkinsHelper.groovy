#!groovy

/**
 * Check if the current build should execute the pipline for pull request
 *
 * @return "true" in case of pull request pipline, otherwise "false"
 */
def isBuildForPullRequest() {
    return env.CHANGE_ID != null
}

/**
 * Check if the current build should execute the pipline for master branch
 *
 * @return "true" in case of master branch pipline, otherwise "false"
 */
def isBuildForMasterBranch() {
    return env.BRANCH_NAME == 'master'
}

/**
 * Check if the current build should execute the pipline for stable-master branch
 *
 * @return "true" in case of master branch pipline, otherwise "false"
 */
def isBuildForStableMasterBranch() {
    return env.BRANCH_NAME == 'stable-master'
}

/**
 * Check if the branch is an release candidate branch, the execute the pipeline
 *
 * @return "true" in case of release candidate  branch pipline, otherwise "false"
 * release candidate branches are : integration/ or integration- or int/ or hotfix/ or release/
 */
def isBuildForReleaseCandidateBranch() {
    return (env.BRANCH_NAME ==~ /integration\/.*/
        || env.BRANCH_NAME ==~ /integration\-.*/
        || env.BRANCH_NAME ==~ /int\/.*/
        || env.BRANCH_NAME ==~ /hotfix\/.*/
        || env.BRANCH_NAME ==~ /release\/.*/
        || env.BRANCH_NAME ==~ /rc\/.*/
    )
}


/**
* Check if the build for integration,hotfix,release,master and PR
* @return true in case of integration,hotfix,release,master and PR
*/
def executeUnitTest(){
    return (isBuildForReleaseCandidateBranch()
        || isBuildForMasterBranch()
        || isBuildForStableMasterBranch()
        || isBuildForPullRequest()
        || params.executeUnittest
    )
}

/**
 * Check if the current build is for a nightly/* branch
 *
 * @return "true" in case of a nightly/* branch, for which more extensive tests should run
 */
def isBuildForNightlyTest() {
    return ( env.BRANCH_NAME ==~ /nightly\/.*/ )
}

/**
 * Run the command in sh block.
 * @param cmd
 * @return
 */
def mysh(cmd) {
    sh('#!/bin/sh -e\n' + cmd)
}

/**
 * Replace the '/' to '-' in branch name
 * @param branch
 * @return
 */
def modifyBranchName(branch) {
    branch = branch.replaceAll("/","-")
    return branch.replaceAll("_", "-")
}

/**
 * Return the current Month
 * @return
 */
def currentMonth() {
    Formatter fmt = new Formatter();
    Calendar cal = Calendar.getInstance();
    fmt.format("%tb", cal);
    return fmt
}

/**
 * Return the Current Year
 * @return
 */
def currentYEAR() {
    Calendar c = Calendar.getInstance();
    int currYear = c.get(Calendar.YEAR);
    return currYear
}

/**
 * Return the Current Day like JAN, FEB..
 * @return
 */
def currentDAY() {
    Calendar c = Calendar.getInstance();
    int currDay = c.get(Calendar.DAY_OF_MONTH);
    return currDay
}

/**
 * Update the Package version
 * @param workspace
 * @param branch
 * @param buildNumber
 * @param gitHash
 * @return
 */
def updatePackageVersionTask(workspace, branch, buildNumber, gitHash) {
    def version = load "${workspace}/utilities/jenkins/version.groovy"
    def localversion = version.getReleaseNumber(branch)

    def shellScript =  """
        cd ${workspace}/utilities/add-version/
        npm install
        npm start -- ${workspace} ${branch} ${buildNumber} ${gitHash} ${localversion}
    """
    mysh(shellScript)
}

/*Create a version.json file out of utilities/rxmachine-deploy/release-config.example.json*/
def createReleaseVersionFile(workspace) {
    def version = load "${workspace}/utilities/jenkins/version.groovy"
    def localversion = version.getReleaseNumber(modifyBranchName(env.BRANCH_NAME))

    def shellScript = """
        cd ${workspace}/utilities/rxmachine-deploy/
        sed 's/0.0.1/${localversion}/g' release-config.example.json > version.json
    """
    mysh(shellScript)
}

/**
 * Return the s3 upload version which will be use for uploading the artifact on s3
 * @param version
 * @return
 */
def s3UploadVersion(version) {
    def path = modifyBranchName(env.BRANCH_NAME) + "/" + currentMonth() + "-" + currentDAY() + "-" + currentYEAR() + "-" + version
    return path
}

/**
 * Upload the Artifact on s3
 * @return
 */
def uploadArtifactOnS3() {
    def version = "${BUILD_NUMBER}"
    def s3Destination = s3UploadVersion(version)
    def branchName = modifyBranchName(env.BRANCH_NAME)
    def artifactBucket = "reflexion-artifact"
    def s3Source = "/var/lib/jenkins/jobs/RxOne/branches/${branchName}*/builds/${BUILD_NUMBER}/archive"

    try {
        sh """
       if [ -d ${s3Source} ]; then
            aws s3 cp ${s3Source} s3://${artifactBucket}/${s3Destination} --recursive
          else
            echo "Artifact not found!"
        fi
       """
    } catch (Exception e) {
        println "Exception in Uploading Artifacts " + e.getMessage()
    }
}

/**
 * Download from s3
 * @return
 */
def downloadS3(s3Path, localPath, recursive) {
    if (recursive) {
        sh """
        aws s3 cp s3://${s3Path} ${localPath} --recursive
        """
    } else {
        sh """
        aws s3 cp s3://${s3Path} ${localPath}
        """
    }
}

def copyArtifactOnSlave(source, artifactList) {
    def jenkinsMasterIp = env.jenkinsMasterIP
    try {
            artifactList.each{ nodeName ->
                def destination = nodeName.contains("RxMachine") ? "$HOME/ci/RxMachine" : "$HOME/ci"
                if(nodeName == "RxSuite"){
                    try{
                        sh """
                        mkdir -p $HOME/ci/RxSuite
                        scp -r -o StrictHostKeyChecking=no -i /home/ubuntu/.ssh/jenkins.pem ubuntu@${jenkinsMasterIp}:${source}/${nodeName}/*.AppImage ${destination}/RxSuite
                        """
                    }catch (Exception e) {
                        println "Exception in Uploading Artifacts " + nodeName
                    }

                } else {
                    try{
                        sh """
                        mkdir -p ${destination}
                        scp -r -o StrictHostKeyChecking=no -i /home/ubuntu/.ssh/jenkins.pem ubuntu@${jenkinsMasterIp}:${source}/${nodeName} ${destination}
                        """
                    }catch (Exception e) {
                        println "Exception in Uploading Artifacts " + source
                    }
                }
            }
        }catch (Exception e) {
        println "Exception in Uploading Artifacts " + source
    }
}

def downloadRxdepFromS3(){
    try{
        sh """
        rm -rf /home/ubuntu/workspace/rxdep-native*
        aws s3 cp s3://rx-machine-dependencies/latest/rxdep-native.tar.gz /home/ubuntu/workspace/
        cd /home/ubuntu/workspace/
        tar -xzvf rxdep-native.tar.gz
        """
    }catch(Exception ex){
        error "Exception in downloading rxnative dependencies from S3 " + ex.toString()
        currentBuild.result='FAILURE'
        throw ex
    }
}
def executeRxTesttools() {
    try {
        def destination = "$HOME/ci"
        def shellScript = """
            set -exv
            export LD_LIBRARY_PATH=/home/ubuntu/workspace/rxdep-native/lib:/usr/local/MATLAB/MATLAB_Runtime/v93/runtime/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/bin/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/sys/os/glnxa64:/home/ubuntu/workspace/lib:$HOME/ci/PlannerBackend/PlannerBackendNode
            # export NODE_CONFIG_DIR=/home/ubuntu/ci/MachineData
            export NODE_CONFIG_DIR=$HOME/workspace/RxOne/SimData/merged
            export PB_NODE_CONFIG_DIR=$HOME/workspace/RxOne/SimData/merged
            export PLANNER_PATH=/home/ubuntu/ci/PlannerBackend/PlannerBackendNode
            cd ${destination}
            rm -rf RxDB
            unzip '*.zip'

            cd ${workspace}/RxSuite
            rm -rf node_modules
            npm install

            # Generate machinedata for Linux environments.
            cd ${workspace}/utilities/machinedata-tools/
            npm install
            ./merge-machinedata-kvct-playback.sh

            #drop database reflexion-rt2
            mongo reflexion-rt2 --eval "db.dropDatabase()"

            cd ${workspace}/utilities/start-nodes
            chmod +x start-gateway.sh


            ./start-gateway.sh
            #give time to upload data to db
            sleep 60
            #netstat -ntpl

            cd ${workspace}/utilities/db-tools/
            mongo reflexion-rt2 set-config-to-dev-delivery-workflow.js

            cd ${workspace}/utilities/start-nodes
            chmod +x start-nodes-ci.sh
            ./start-nodes-ci.sh

            ps

            export DISPLAY=:1
            export LD_LIBRARY_PATH=/home/ubuntu/workspace/rxdep-native/lib:/home/ubuntu/workspace/lib:$HOME/ci/PlannerBackend/PlannerBackendNode


            #Lets extract the appimage
            cd /home/ubuntu/ci/RxSuite/

            #Lets run testtool
            if [ -d "squashfs-root" ]; then
                rm -r squashfs-root
            fi
            ./rx-tools.AppImage --appimage-extract
            cd ${workspace}/RxSuite
            npm run webdriver-manager:update
            echo "starting testool"
            npm run e2e:testtool
          """

        mysh(shellScript)
    } catch (Exception ex) {
        error "Exception in executeRxTesttools " + ex.toString()
        currentBuild.result='FAILURE'
        throw ex
    }
}
def executeRxSuite() {
    try {
        def destination = "$HOME/ci"
        def shellScript = """
            set -xv
            export LD_LIBRARY_PATH=/home/ubuntu/workspace/rxdep-native/lib:/usr/local/MATLAB/MATLAB_Runtime/v93/runtime/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/bin/glnxa64:/usr/local/MATLAB/MATLAB_Runtime/v93/sys/os/glnxa64:/home/ubuntu/workspace/lib:$HOME/ci/PlannerBackend/PlannerBackendNode
            # export NODE_CONFIG_DIR=/home/ubuntu/ci/MachineData
            export NODE_CONFIG_DIR=$HOME/workspace/RxOne/SimData/merged
            export PB_NODE_CONFIG_DIR=$HOME/workspace/RxOne/SimData/merged
            export PLANNER_PATH=/home/ubuntu/ci/PlannerBackend/PlannerBackendNode
            cd ${destination}
            rm -rf RxDB
            unzip '*.zip'

            cd ${workspace}/RxSuite
            rm -rf node_modules
            npm install

            # Generate machinedata for Linux environments.
            cd ${workspace}/utilities/machinedata-tools/
            npm install
            ./merge-machinedata-kvct-playback.sh

            #drop database reflexion-rt2
            mongo reflexion-rt2 --eval "db.dropDatabase()"

            cd ${workspace}/utilities/start-nodes
            chmod +x start-gateway.sh


            ./start-gateway.sh
            #give time to upload data to db
            sleep 60
            #netstat -ntpl

            cd ${workspace}/utilities/db-tools/
            mongo reflexion-rt2 set-config-to-dev-delivery-workflow.js

            cd ${workspace}/utilities/start-nodes
            chmod +x start-nodes-ci.sh
            ./start-nodes-ci.sh

            ps

            export DISPLAY=:1
            export LD_LIBRARY_PATH=/home/ubuntu/workspace/rxdep-native/lib:/home/ubuntu/workspace/lib:$HOME/ci/PlannerBackend/PlannerBackendNode

            # Clean up the squashfs-root so that Planning tests can be executed
            if [ -d "squashfs-root" ]; then
                rm -r squashfs-root
            fi

            #Execute planning tests
            cd /home/ubuntu/ci/RxSuite/
            ./rx-suite.AppImage --appimage-extract
            cd ${workspace}/RxSuite
            npm run webdriver-manager:update

            #echo "starting planning"
            npm run e2e:ci -- --suite planning

            #echo "starting delivery"
            #npm run e2e:ci -- --suite delivery
          """

        mysh(shellScript)
    } catch (Exception ex) {
        error "Exception in executeRxSuite " + ex.toString()
        currentBuild.result='FAILURE'
        throw ex
    }
}
/**
 * Zip the release product artifacts
 */
def zipReleaseArtifact(){
    def branchName = modifyBranchName(env.BRANCH_NAME)
    def source = "/var/lib/jenkins/jobs/RxOne/branches/${branchName}*/builds/${BUILD_NUMBER}/archive"

    try {
        sh """
       if [ -d ${source} ]; then
            cd  ${source}
            if [ -d RxGateway/rxdep-native ]; then
                mv RxGateway/rxdep-native .
            fi
            mkdir rxdeploy
            cp -R utilities/rxmachine-deploy/dist/config rxdeploy/
            cp -R utilities/rxmachine-deploy/dist/rxdeploy rxdeploy/
            cp -R utilities/rxmachine-deploy/version.json rxdeploy/
            zip -m -r ${branchName}-${BUILD_NUMBER}.zip RxMachine/build-QNX_arm \
                RxMachine/build-QNX_x86 \
                RxMachine/build-UBUNTU_x86/LogNode \
                RxMachine/build-UBUNTU_x86/MonitorNode \
                MachineData \
                PlannerBackend/PlannerBackendNode \
                PlannerBackend/DeliveryBackendNode \
                RxSuite/rx-suite.AppImage \
                RxGateway \
                rxdeploy \
                rxdep-native \
                -x *.log
          else
            echo "Artifact not found!"
        fi
       """
    } catch (Exception e) {
        println "Exception while zipping the release Artifacts " + e.getMessage()
        currentBuild.result='FAILURE'
        throw e
    }

}

def zipTestToolArtifact(){
    def branchName = modifyBranchName(env.BRANCH_NAME)
    def source = "/var/lib/jenkins/jobs/RxOne/branches/${branchName}*/builds/${BUILD_NUMBER}/archive"

    try {
        sh """
       if [ -d ${source} ]; then
            cd  ${source}
            zip -m -r ${branchName}-${BUILD_NUMBER}-testtool.zip RxSuite/rx-tools.AppImage \
                -x *.log
          else
            echo "Artifact not found!"
        fi
       """
    } catch (Exception e) {
        println "Exception while zipping the zipTestToolArtifact Artifacts " + e.getMessage()
        currentBuild.result='FAILURE'
        throw e
    }

}

/**
 * Zip the deploy artifact
 */
def zipDeployArtifact(){
    def branchName = modifyBranchName(env.BRANCH_NAME)
    def source = "/var/lib/jenkins/jobs/RxOne/branches/${branchName}*/builds/${BUILD_NUMBER}/archive"

    try {
        sh """
       if [ -d ${source} ]; then
            cd  ${source}
            mkdir rxdeploy
            mv utilities/rxmachine-deploy/dist/config rxdeploy/
            mv utilities/rxmachine-deploy/dist/rxdeploy rxdeploy/
            mv utilities/rxmachine-deploy/version.json rxdeploy/
            zip -m -r ${branchName}-${BUILD_NUMBER}-rxdeploy.zip rxdeploy
          else
            echo "Artifact not found!"
        fi
       """
    } catch (Exception e) {
        println "Exception while zipping the deploy Artifacts " + e.getMessage()
        currentBuild.result='FAILURE'
        throw e
    }

}

/**
 * Zip the sim artifact
 */
def zipSimArtifact(){
    def branchName = modifyBranchName(env.BRANCH_NAME)
    def source = "/var/lib/jenkins/jobs/RxOne/branches/${branchName}*/builds/${BUILD_NUMBER}/archive"

    try {
        sh """
       if [ -d ${source} ]; then
            cd  ${source}
            ls -al
            if [ -d ${source}/SimData/merged ]; then
                mv SimData/merged MachineData
            fi
            mv SimData SimDataSource
            mkdir -p SimData/kvct-playback/
            mkdir -p SimData/simulated-pet/
            ls -la SimDataSource/
            ls -la SimDataSource/kvct-playback/
            mv SimDataSource/kvct-playback/*.dat SimData/kvct-playback/
            mv SimDataSource/kvct-playback/*.bin SimData/kvct-playback/
            mv SimDataSource/simulated-pet/*.bin SimData/simulated-pet/
            zip -m -r ${branchName}-${BUILD_NUMBER}-sim.zip RxMachine/build-UBUNTU_x86 \
                SimData \
                MachineData \
                utilities/start-nodes \
                utilities/db-tools \
                -x *.log
        else
            echo "Artifact not found!"
        fi
       """
    } catch (Exception e) {
        println "Exception while zipping the sim Artifacts " + e.getMessage()
        currentBuild.result='FAILURE'
        throw e
    }
}

def zipUxlabArtifact(){
    def branchName = modifyBranchName(env.BRANCH_NAME)
    def source = "/var/lib/jenkins/jobs/RxOne/branches/${branchName}*/builds/${BUILD_NUMBER}/archive"

    try {
        sh """
       if [ -d ${source} ]; then
            cd  ${source}
            ls -al
            if [ -d ${source}/SimData/merged-uxlab ]; then
                mv SimData SimDataSource
            fi
            if [ -d ${source}/SimDataSource/merged-uxlab ]; then
                mv SimDataSource/merged-uxlab MachineData
                rm -r SimDataSource
            fi
            zip -m -r ${branchName}-${BUILD_NUMBER}-uxlab.zip RxMachine/build-UBUNTU_x86 \
                MachineData \
                utilities/start-nodes \
                utilities/db-tools \
                -x *.log
        else
            echo "Artifact not found!"
        fi
       """
    } catch (Exception e) {
        println "Exception while zipping the sim Artifacts " + e.getMessage()
        currentBuild.result='FAILURE'
        throw e
    }
}

/**
 * Zip the others artifact
 */
def zipOthersArtifact(){
    def branchName = modifyBranchName(env.BRANCH_NAME)
    def source = "/var/lib/jenkins/jobs/RxOne/branches/${branchName}*/builds/${BUILD_NUMBER}/archive"
    def dbArtifactName = "${branchName}-${BUILD_NUMBER}-RxDB.zip"
    def releaseArtifactName = "${branchName}-${BUILD_NUMBER}.zip"
    def deployArtifactName = "${branchName}-${BUILD_NUMBER}-rxdeploy.zip"
    def deploySimName= "${branchName}-${BUILD_NUMBER}-sim.zip"
    def uxlab = "${branchName}-${BUILD_NUMBER}-uxlab.zip"
    def testtool = "${branchName}-${BUILD_NUMBER}-testtool.zip"

    try {
        sh """
       if [ -d ${source} ]; then
            cd  ${source}
            find . -type d -empty -delete
            zip -m -r ${branchName}-${BUILD_NUMBER}-others.zip . -x ${dbArtifactName} ${releaseArtifactName} ${deployArtifactName} ${deploySimName} ${uxlab} ${testtool}
          else
            echo "Artifact not found!"
        fi
       """
    } catch (Exception e) {
        println "Exception while zipping the Others Artifacts " + e.getMessage()
        currentBuild.result='FAILURE'
        throw e
    }
}
/**
 * Delete RxDB Artifact If the Job is unstable or Fail
 */
def deleteArtifacts(){
    def branchName = modifyBranchName(env.BRANCH_NAME)
    def source = "/var/lib/jenkins/jobs/RxOne/branches/${branchName}*/builds/${BUILD_NUMBER}/archive"

    try {
        sh """
       if [ -f ${source}/${branchName}-${BUILD_NUMBER}-RxDB.zip ]; then
            cd  ${source}
            rm ${branchName}-${BUILD_NUMBER}-RxDB.zip
          else
            echo "Artifact not found!"
        fi
       """
    } catch (Exception e) {
        println "Exception while deleting the Others Artifacts " + e.getMessage()
    }
}

def bundleBuildLogs(){
    def branchName = modifyBranchName(env.BRANCH_NAME)
    def source = "/var/lib/jenkins/jobs/RxOne/branches/${branchName}*/builds/${BUILD_NUMBER}"
    try {
        sh """
       if [ -d ${source} ]; then
            cd  ${source}
            mkdir -p archive/build-logs
            cp *.xml archive/build-logs
            cd archive
            zip -m -r ${branchName}-${BUILD_NUMBER}-build-logs.zip build-logs
          else
            echo "Artifact not found!"
        fi
       """
    } catch (Exception e) {
        println "Exception while copying the build logs " + e.getMessage()
        currentBuild.result='FAILURE'
        throw e
    }
}

def createS3PresignedURL(){
    def version = "${BUILD_NUMBER}"
    def branchName = modifyBranchName(env.BRANCH_NAME)
    def dbArtifactName = "${branchName}-${BUILD_NUMBER}-RxDB.zip"
    def releaseArtifactName = "${branchName}-${BUILD_NUMBER}.zip"
    def deployArtifactName = "${branchName}-${BUILD_NUMBER}-deploy.zip"
    def otherArtifactName = "${branchName}-${BUILD_NUMBER}-others.zip"
    def simArtifactName = "${branchName}-${BUILD_NUMBER}-sim.zip"
    def uxlabArtifactName = "${branchName}-${BUILD_NUMBER}-uxlab.zip"
    def s3Destination = s3UploadVersion(version)
    def artifactBucket = "reflexion-artifact"

    def dbURL = s3PresignURL(bucket: artifactBucket, key: s3Destination+"/${dbArtifactName}", durationInSeconds: 604800)
    def releaseURL = s3PresignURL(bucket: artifactBucket, key: s3Destination+"/${releaseArtifactName}", durationInSeconds: 604800)
    def deployURL = s3PresignURL(bucket: artifactBucket, key: s3Destination+"/${deployArtifactName}", durationInSeconds: 604800)
    def othersURL = s3PresignURL(bucket: artifactBucket, key: s3Destination+"/${otherArtifactName}", durationInSeconds: 604800)
    def simURL = s3PresignURL(bucket: artifactBucket, key: s3Destination+"/${simArtifactName}", durationInSeconds: 604800)
    def uxlabURL = s3PresignURL(bucket: artifactBucket, key: s3Destination+"/${uxlabArtifactName}", durationInSeconds: 604800)

    slackSend color: '#439FE0', message: "DB Artifact ${branchName}-${BUILD_NUMBER} : ${dbURL}"
    slackSend color: '#439FE0', message: "Artifact ${branchName}-${BUILD_NUMBER} : ${releaseURL}"
    slackSend color: '#439FE0', message: "RxMachineDeploy Artifact ${branchName}-${BUILD_NUMBER} : ${deployURL}"
    slackSend color: '#439FE0', message: "Others Artifact ${branchName}-${BUILD_NUMBER} : ${othersURL}"
    slackSend color: '#439FE0', message: "Sim Artifact ${branchName}-${BUILD_NUMBER} : ${simArtifactName}"
    slackSend color: '#439FE0', message: "UxLab Artifact ${branchName}-${BUILD_NUMBER} : ${uxlabArtifactName}"
}

def tagVersion(){
    def branchName = jenkinsHelper.modifyBranchName(env.BRANCH_NAME)
    return "${branchName}-${BUILD_NUMBER}-"+currentMonth() + "-" + currentDAY() + "-" + currentYEAR()
}

return this
