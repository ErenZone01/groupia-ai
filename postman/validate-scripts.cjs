const fs=require('node:fs');
const path=require('node:path');
const collection=JSON.parse(fs.readFileSync(path.join(__dirname,'Groupia-local.postman_collection.json'),'utf8'));
let scripts=0;
function visit(item) {
 for(const event of item.event || []) {
  // Legacy globals remain reserved by the Postman sandbox.
  new Function('pm','request','responseBody','responseCode','responseTime','responseHeaders','tests','iteration','postman',event.script.exec.join('\n'));
  scripts++;
 }
 for(const child of item.item || [])visit(child);
}
visit(collection);
console.log(scripts+' scripts Postman : syntaxe et noms reserves valides.');

