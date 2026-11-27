import fs from 'node:fs/promises';
import {finalizePresentation} from 'file:///C:/Users/Deepak%20Kumar/.codex/plugins/cache/openai-primary-runtime/presentations/26.1123.11515/skills/presentations/container_tools/artifact_tool_utils.mjs';
const w='C:/workspace/oracle-demo/AI/AiHelpDeskApp';
const sk='C:/Users/Deepak Kumar/.codex/plugins/cache/openai-primary-runtime/presentations/26.1123.11515/skills/presentations';
console.log(await finalizePresentation({workspaceDir:w,candidatePath:w+'/.demo-build/candidate.pptx',finalPath:w+'/demo-output/AiHelpDeskApp_Demo_Final.pptx',pythonExecutable:'C:/Users/Deepak Kumar/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe',integrityValidatorPath:sk+'/container_tools/inspect_presentation_package_integrity.py',layoutValidatorPath:sk+'/container_tools/inspect_presentation_layout_geometry.py',layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-heading-fit'],fontPolicy:{basis:'design',families:['Arial']},verifyArtifactToolImport:true,receiptPath:w+'/.demo-build/validation-updated.json'}));

