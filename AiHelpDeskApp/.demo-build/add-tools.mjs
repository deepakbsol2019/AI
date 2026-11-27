import fs from 'node:fs';
let s=fs.readFileSync('.demo-build/build.mjs','utf8');
const groups=[
['@Tool table: ticket actions',[
['createTicket','Creates an OPEN ticket and assigns support.'],
['reopenTicket','Changes CLOSED to OPEN and refreshes updatedOn.'],
['updateTicket','Updates summary and priority on an existing ticket.'],
['findTicketById','Loads one saved ticket by its exact ID.'],
['findTicketsBySummary','Finds literal summary matches for one creator.']]],
['@Tool table: ticket searches',[
['findTicketCreatedByUserName','Lists tickets created by the exact username.'],
['findTicketAssignedToUserName','Lists tickets assigned to the exact username.'],
['findTicketByStatus','Lists OPEN, INPROGRESS or CLOSED tickets.'],
['findTicketByTicketPriority','Lists tickets with LOW, MEDIUM or HIGH priority.'],
['findTicketByCreatedOn','Lists tickets created on a calendar date.']]],
['@Tool table: users and server time',[
['findHelpdeskUserByUserName','Loads a helpdesk user by exact username.'],
['findTicketAssignedTo','Selects SUPPORT user by workload ranking.'],
['getCurrentDateTime','Returns the current server date and time.'],
['getTodayDate','Returns the current server calendar date.']]]
];
let code='';
for(const [title,rows] of groups){
 const note='Registered through .tools(ticketTools, generalTool). '+rows.map(r=>r[0]+': '+r[1]).join(' ')+' createTicket, reopenTicket and updateTicket require explicit user confirmation in their tool instructions. updateTicket currently assigns optional fields unconditionally. Summary search is literal and case-insensitive, not semantic search. Time tools use the server timezone. findTicketAssignedTo ranks fewest OPEN, then fewest INPROGRESS, then most total tickets, then lowest user ID. Lookup tools marked returnDirect=true may return ticket data directly.';
 code+=`s=slide(${JSON.stringify(title)},${JSON.stringify(note)},'TicketTools.java; GeneralTool.java; TicketToolsImpl.java; GeneralToolImpl.java');\n`;
 code+=`{const vals=${JSON.stringify([['Tool name','What it does'],...rows])};const tb=s.tables.add({rows:vals.length,columns:2,left:64,top:175,width:1152,height:420,columnWidths:[490,662],values:vals});tb.borders.assign({style:'solid',fill:'#CCD9DF',width:1});for(let r=0;r<vals.length;r++){for(let c=0;c<2;c++){const cell=tb.getCell(r,c);cell.fill=r===0?'#14283F':r%2?'#E2F0F0':'#FFFFFF';cell.text.style={fontFamily:'Arial',fontSize:24,color:r===0?'#FFFFFF':'#23364A',bold:r===0};}}}\n`;
}
s=s.replace("s=slide('Demo readiness and next steps'",code+"s=slide('Demo readiness and next steps'");
s=s.replace("arrow(s,235,280,55,true);arrow(s,900,280,55,true);", "s.shapes.add({geometry:'line',position:{left:250,top:310,width:665,height:0},line:{fill:teal,width:2}});s.shapes.add({geometry:'line',position:{left:915,top:264,width:0,height:46},line:{fill:teal,width:2}});arrow(s,235,310,55,true);arrow(s,900,310,55,true);");
fs.writeFileSync('.demo-build/build.mjs',s);
let f=fs.readFileSync('.demo-build/finalize-updated.mjs','utf8').replace('AiHelpDeskApp_Demo_Final.pptx','AiHelpDeskApp_Demo_With_Tools.pptx').replace('validation-updated.json','validation-tools.json').replace('workspaceDir:w,candidatePath:', 'requiredNativeTableOwnerSlides:[11,12,13],workspaceDir:w,candidatePath:');fs.writeFileSync('.demo-build/finalize-tools.mjs',f);
