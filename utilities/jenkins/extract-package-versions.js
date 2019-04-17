//Copy this to RxSuite,RxGateway and ensure to change outPutFileName and run: node extract-package-version.js
var fs = require('fs');
var path = require('path');
var packageJson = require('./package.json');
var outPutFileName = 'pVersion-rxSuite.txt'

strictifyDeps('dependencies');
strictifyDeps('devDependencies');
console.log('done!');

function strictifyDeps(depsProperty) {
  var deps = Object.keys(packageJson[depsProperty]);
  deps.forEach(function(dep) {
    console.log("processing " + dep);
    var depPackageJson = require('./node_modules/' + dep + '/package.json');
    packageJson[depsProperty][dep] = depPackageJson.version;
  });

  fs.writeFileSync(path.resolve(__dirname,outPutFileName), JSON.stringify(packageJson,null,2));
}