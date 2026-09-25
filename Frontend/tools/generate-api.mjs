import {readFile,writeFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
const offline=process.argv.includes('--snapshot');
if(!offline){
 const url=process.env.GROUPIA_OPENAPI_URL||'http://localhost:8080/v3/api-docs';
 const response=await fetch(url,{signal:AbortSignal.timeout(20000)});
 if(!response.ok)throw new Error('OpenAPI indisponible (HTTP '+response.status+').');
 const schema=await response.json();
 if(!schema.openapi||!schema.paths)throw new Error('Document OpenAPI invalide.');
 await writeFile('openapi.json',JSON.stringify(schema,null,2)+'\n');
}
const schema=JSON.parse(await readFile('openapi.json','utf8'));
const jsonResponses={};
for(const [path,operations] of Object.entries(schema.paths)){
 // Spring's unspecified produces is */*. These structured DTO responses are JSON.
 if(Object.values(operations).some(operation=>Object.entries(operation.responses??{}).some(([status,response])=>/^2/.test(status)&&response.content?.['*/*']?.schema)))
  jsonResponses[path]={toUse:'json'};
}
const result=spawnSync(process.execPath,['node_modules/ng-openapi-gen/lib/index.js','--config','ng-openapi-gen.json','--customizedResponseType',JSON.stringify(jsonResponses)],{stdio:'inherit'});
process.exit(result.status??1);
