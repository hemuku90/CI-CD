#!groovy
 def getReleaseNumber(branchName){
    def MAJOR="0"
    def MINOR="0"
    def ITERATION="0"

    //Set version number only if its a release branch.
    if (env.BRANCH_NAME ==~ /release\/.*/) {
        return "${MAJOR}.${MINOR}.${ITERATION}-${env.BUILD_NUMBER}"
    }
    else {
        return "0.0.0-${env.BUILD_NUMBER}-${branchName}-${env.GIT_COMMIT}"
    }
 }
 return this