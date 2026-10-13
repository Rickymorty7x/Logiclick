const $ = (id) => document.getElementById(id);
const state = {connected:false, proposal:null, pointer:null, frameDataUrl:null, timer:null, lastCapturedAt:0, sessionToken:null};
const hostedOnPi = location.pathname.startsWith('/controller');
$('apiBase').value = hostedOnPi ? location.origin : 'https://ricky.dusky-parore.ts.net';

function config(){return{base:$('apiBase').value.trim().replace(/\/$/,''),device:$('deviceId').value.trim(),token:state.sessionToken||$('adminToken').value.trim()}}
function headers(json=false){const h={Authorization:`Bearer ${config().token}`};if(json)h['Content-Type']='application/json';return h}
function url(path){return `${config().base}/v1/devices/${encodeURIComponent(config().device)}${path}`}
function toast(message){$('toast').textContent=message;$('toast').classList.add('show');setTimeout(()=>$('toast').classList.remove('show'),2200)}
function log(message){const li=document.createElement('li');li.textContent=message;const time=document.createElement('time');time.textContent=new Date().toLocaleTimeString();li.append(time);$('activity').prepend(li);while($('activity').children.length>14)$('activity').lastChild.remove()}
function setConnected(value){state.connected=value;$('connectionDot').classList.toggle('connected',value);$('connectionLabel').textContent=value?'Gateway connected':'Disconnected'}
async function pasteToken(connectAfter=false){const input=$('adminToken');if(!navigator.clipboard?.readText){input.focus();toast('Clipboard access is unavailable — press ⌘/Ctrl+V');return}try{const token=(await navigator.clipboard.readText()).trim();if(!token){toast('Clipboard does not contain a token');return}input.value=token;log('Admin token pasted from clipboard');toast('Token pasted');if(connectAfter)await connect()}catch{input.focus();toast('Clipboard blocked — press ⌘/Ctrl+V')}}

async function api(path,options={}){const response=await fetch(url(path),options);if(!response.ok){let message=`HTTP ${response.status}`;try{message=(await response.json()).error||message}catch{}throw new Error(message)}return response}

async function bootstrapLocalSession(){if(!hostedOnPi)return;const response=await fetch('/v1/controller/session',{cache:'no-store'});if(!response.ok)throw new Error(`Direct session unavailable (HTTP ${response.status})`);const body=await response.json();if(!body.token)throw new Error('Direct session unavailable');state.sessionToken=body.token}
async function connect(){try{if(hostedOnPi)await bootstrapLocalSession();if(!config().base||!config().device||!config().token){toast('Enter the API URL, device ID, and admin token');return}const response=await fetch(`${config().base}/v1/health`,{cache:'no-store'});if(!response.ok)throw new Error(`HTTP ${response.status}`);setConnected(true);$('deviceTitle').textContent=config().device;log(hostedOnPi?'Connected automatically in direct mode':'Connected to the Logiclick gateway');await refreshFrame()}catch(error){setConnected(false);log(`Connection failed: ${error.message}`);toast(error.message.includes('Failed to fetch')?'Gateway unreachable — connect this device to Tailscale':error.message)}}

async function refreshFrame(){if(!state.connected)return;try{const response=await api('/mirror/latest',{headers:headers(),cache:'no-store'});const blob=await response.blob();const previous=$('screenImage').src;const objectUrl=URL.createObjectURL(blob);$('screenImage').src=objectUrl;$('screen').classList.add('has-frame');if(previous.startsWith('blob:'))URL.revokeObjectURL(previous);state.lastCapturedAt=Number(response.headers.get('X-Captured-At'))||Date.now();state.frameDataUrl=await blobToDataUrl(blob);setConnected(true)}catch(error){if(!String(error.message).includes('No mirror frame'))log(`Frame refresh: ${error.message}`)}finally{scheduleFrame()}}
function scheduleFrame(){clearTimeout(state.timer);if($('liveRefresh').checked)state.timer=setTimeout(refreshFrame,1000)}
function blobToDataUrl(blob){return new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.onerror=reject;r.readAsDataURL(blob)})}

async function sendAction(action,label='Action'){try{await api('/commands',{method:'POST',headers:headers(true),body:JSON.stringify(action)});log(`${label} queued`);toast(`${label} sent`);return true}catch(error){log(`${label} failed: ${error.message}`);toast(error.message);return false}}
function area(x,y){const r=Number($('tapRadius').value)/100;return{left:Math.max(0,x-r),top:Math.max(0,y-r),right:Math.min(1,x+r),bottom:Math.min(1,y+r)}}
function point(event){const screen=$('screen'),img=$('screenImage'),rect=screen.getBoundingClientRect();if(!img.naturalWidth||!img.naturalHeight)return null;const imageRatio=img.naturalWidth/img.naturalHeight,boxRatio=rect.width/rect.height;let width=rect.width,height=rect.height,left=rect.left,top=rect.top;if(imageRatio>boxRatio){height=width/imageRatio;top+=(rect.height-height)/2}else{width=height*imageRatio;left+=(rect.width-width)/2}if(event.clientX<left||event.clientX>left+width||event.clientY<top||event.clientY>top+height)return null;return{x:(event.clientX-left)/width,y:(event.clientY-top)/height,px:event.clientX-rect.left,py:event.clientY-rect.top}}
function showTap(p){const marker=$('tapMarker');marker.style.left=`${p.x*100}%`;marker.style.top=`${p.y*100}%`;marker.classList.remove('show');void marker.offsetWidth;marker.classList.add('show')}
function showLine(a,b){const line=$('gestureLine');const dx=b.px-a.px,dy=b.py-a.py;line.style.display='block';line.style.left=`${a.px}px`;line.style.top=`${a.py}px`;line.style.width=`${Math.hypot(dx,dy)}px`;line.style.transform=`rotate(${Math.atan2(dy,dx)}rad)`;setTimeout(()=>line.style.display='none',500)}

$('screen').addEventListener('pointerdown',event=>{if(!$('screen').classList.contains('has-frame'))return;state.pointer=point(event);if(state.pointer)$('screen').setPointerCapture(event.pointerId)});
$('screen').addEventListener('pointerup',event=>{if(!state.pointer)return;const end=point(event),start=state.pointer;state.pointer=null;if(!end)return;const distance=Math.hypot(end.x-start.x,end.y-start.y);if(distance<.025){showTap(end);sendAction({type:'tap',coordinateSpace:'normalized',area:area(end.x,end.y),holdMs:{min:55,max:95}},'Tap')}else{showLine(start,end);const duration=Number($('dragDuration').value);sendAction({type:'drag',coordinateSpace:'normalized',startArea:area(start.x,start.y),endArea:area(end.x,end.y),durationMs:{min:Math.max(80,duration-50),max:duration+50}},'Drag')}});

document.querySelectorAll('[data-global]').forEach(button=>button.addEventListener('click',()=>sendAction({type:'global_action',action:button.dataset.global},button.textContent.trim())));
$('connectButton').addEventListener('click',connect);
$('pasteTokenButton').addEventListener('click',()=>pasteToken(false));
$('refreshButton').addEventListener('click',refreshFrame);
$('liveRefresh').addEventListener('change',scheduleFrame);
$('deviceId').addEventListener('input',()=>$('deviceTitle').textContent=$('deviceId').value||'device');
$('tapRadius').addEventListener('input',()=> $('radiusValue').textContent=`${Number($('tapRadius').value).toFixed(2)}%`);
$('dragDuration').addEventListener('input',()=> $('durationValue').textContent=`${$('dragDuration').value} ms`);

$('proposeButton').addEventListener('click',async()=>{const goal=$('goal').value.trim();if(!goal){toast('Describe the goal first');return}if(!state.frameDataUrl){toast('Wait for a screen frame first');return}$('proposeButton').disabled=true;$('proposeButton').textContent='Analyzing…';try{const response=await api('/intelligence/propose',{method:'POST',headers:headers(true),body:JSON.stringify({goal})});const proposal=await response.json();state.proposal=proposal;$('confidence').textContent=`${Math.round((proposal.confidence||0)*100)}%`;$('rationale').textContent=proposal.rationale||'No rationale returned.';$('proposalJson').textContent=JSON.stringify(proposal.action,null,2);$('proposalPanel').hidden=false;log('Intelligence layer proposed one action');if(!$('requireApproval').checked){await executeProposal()}}catch(error){log(`Intelligence request failed: ${error.message}`);toast(error.message)}finally{$('proposeButton').disabled=false;$('proposeButton').textContent='Analyze current frame'}});
async function executeProposal(){if(!state.proposal)return;if(await sendAction(state.proposal.action,'AI proposal')){$('proposalPanel').hidden=true;state.proposal=null}}
$('approveButton').addEventListener('click',executeProposal);
$('rejectButton').addEventListener('click',()=>{$('proposalPanel').hidden=true;state.proposal=null;log('AI proposal rejected')});
$('clearLog').addEventListener('click',()=> $('activity').replaceChildren());
document.addEventListener('keydown',event=>{if((event.metaKey||event.ctrlKey)&&event.shiftKey&&event.key.toLowerCase()==='v'){event.preventDefault();pasteToken(true)}});
setInterval(()=>{if(!state.lastCapturedAt)return;const seconds=Math.max(0,Math.floor((Date.now()-state.lastCapturedAt)/1000));$('frameAge').textContent=seconds<2?'Live frame':`${seconds}s old`},1000);

window.logiclick={sendAction,refreshFrame};

if(hostedOnPi){$('directControllerLink').hidden=true;$('apiBaseLabel').hidden=true;$('tokenLabel').hidden=true;$('tokenHelp').hidden=true;$('directModeNote').hidden=false;$('connectButton').textContent='Reconnect';setTimeout(connect,0)}
