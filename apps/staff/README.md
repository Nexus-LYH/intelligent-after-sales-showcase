# 员工管理端 · 当前至第七批

当前范围：真实认证、资产/客户开通、客户报修及私有附件，以及受限工单队列、受理/分派/记录和第七批解决方案、原报修人决定及客服核验关闭。下文前批说明为历史范围，当前以末节第七批和共享记录为准。不扩待领池、转派、补充、重开、评价或记录新附件。

Node 24，npm；依赖由package-lock.json固定。`npm ci`、`npm run typecheck`、`npm test`、`npm run build`。`npm run dev`只监听127.0.0.1:5173，`/staff/`为员工应用；`/customer/`转发内部customer Vite 5180，`/api`同源代理gateway 127.0.0.1:8080，不改写Origin，不关闭来源或CSRF校验。使用127.0.0.1入口，不混用localhost。后端第四批可信链需已就绪，既有账号保留原密码/状态，且、显式允许5173 Origin，运行占用按共享沟通记录协调。

Cookie由浏览器接收，不经JS读取/持久化。首次控制链login→me→AUTH csrf串行；CSRF仅内存。sessionStorage只存非秘密登录预期及服务端截止。遇到503/未知结果隐藏旧正文，不自动重放密码或普通写入。撤权未知保留本次原键/目标/版本/内容在内存，重新核验后明确恢复；页面刷新会丢失此内存动作，请勿在未知结果期间刷新或关闭。

路由：/login、/platform/login、/account、/account/password、/users、/users/:id。普通员工落本人账户，受限账号只改密/退出；平台管理能力独立，不能查询租户账号。菜单与守卫只是交互，服务端复验权限。

`npm run browser`需要本机Chrome、已就绪后端和受控初始化账号，实际访问5173；使用两个以上独立浏览器上下文，不跑JUnit fixture或SQL。初始秘密从既有项目外受限目录读取，改密后的验收秘密只保存于同一受限目录下本角色的frontend-browser-passwords.json，不写源码、.env、命令行、日志或证据。可通过IAS_BROWSER_SECRET_DIR指定既有受限目录。测试会修改本地验收账号密码并撤销目标会话；必须先在沟通记录独占运行窗口，禁止与数据库故障测试并行。

浏览器证据默认在忽略的runtime/browser；IAS_BROWSER_EVIDENCE可指定本角色协作证据目录。证据只记录断言、接口路径/状态及无秘密的页面截图，不启用带凭据的trace/HAR。Mock/拦截异常交互测试与真实后端证据分别登记，不能互相替代。

当前第四批目标为local浏览器→5173→gateway8080→TLS identity8441/asset8442。前端不持有服务凭据或修改TLS信任；实际验证结果以最新共享记录为准，不凭目标拓扑声称内部链路验收通过；不推送或部署。

## ID-03回调归属回归

`request-scope.ts`将请求绑定到身份generation、actor/namespace、登录attempt及调用方有效标识。详情页再绑定组件存续、路由和本次提交序号；清pending比较原键/目标/版本/原因/actor。旧success/catch/finally不得修改新身份、新操作、提示或busy。合法自撤先在仍拥有回执时结束自身局部busy，再清身份跳登录。

未知撤权的原键/内容仅在本标签页内存按actor/namespace保留，换身份不展示，回原身份后允许显式恢复。重挂载只恢复待确认面板，不自动重发。刷新或关闭仍会丢失这份内存状态，不把未知当失败重建动作。

无数据库回调回归：启动Vite后运行`node scripts/id03-original-repro.mjs`及`node scripts/id03-regression.mjs`。后者使用真实Chrome/实际页面、合成HTTP和明确Pinia夹具；覆盖迟到200/401/503、同actor换attempt、新pending、卸载/重挂载与路由复用。路由复用夹具仅在测试内移除忙碌离开拦截以到达该分支，不修改生产导航/授权规则。它不证明真实换账号或数据库故障；真实自撤/409/503回执恢复仍由`npm run browser`另行验证。

## 第四批资产与客户入口

先在apps/customer执行npm ci及npm run dev（内部loopback5180），再在本目录npm run dev（唯一浏览器入口5173）。固定使用http://127.0.0.1:5173/staff/或/customer/，不直接访问5180、不混用localhost。`IAS_GATEWAY_URL`只供开发代理目标配置，默认8080；/api没有直连identity回退。customer HMR也经5173/customer路径。两个应用各自npm run typecheck、npm run build，产物base分别/staff/和/customer/。

新增路由/assets/companies、/assets/models、/assets/devices及各/:id。公开字段/启停/版本/操作恢复遵循原登录契约14。普通客户开通只提交一次性密码到内存请求，结果恢复不读取旧密码；只在nextAction=ENTER_INITIAL_PASSWORD后显式同键重输。未知资产写保留原键、非秘密内容及原版本；查询无副作用，重放必须用户明确选择。刷新后可按原操作键查询；不能把503说成回滚。设备企业/型号/编号/序列号不开放普通编辑；型号不开放停用，无报修/工单假数据。

认证、API、请求scope和公共壳由customer直接复用本目录源码，两产物分别打包，不拷贝另一套登录协议。sessionStorage只存同源非秘密预期（ias-login-expectation），BroadcastChannel使用ias-session，使两个入口共享身份变化检测；一个浏览器上下文只存在一个当前账号，客户/管理员联调用独立上下文。该软协调不能证明迟到Cookie不会覆盖，仍依赖每次me匹配及服务端校验。

无后端UI验证：node scripts/batch4-ui-check.mjs（Chrome合成HTTP），node scripts/id03-regression.mjs（ID-03回归）。它们不读取真实凭据，不算gateway/DB联调。npm run browser为原真实认证链回归，需独占共享后端窗口，可能改变验收账号密码，不可未经协调并行执行。

真实第四批浏览器：后端明确释放共享窗口且三个交付服务已按services/README就绪后，运行`node scripts/batch4-real-check.mjs`（两租户/四企业建档开通、首登改密、范围及撤权）及`node scripts/batch4-real-recovery.mjs`（读取前一脚本结果，真实409/启停及已提交回执替换503的原键查询）。后者的503是受控HTTP回执故障，不是数据库停启。两个脚本均需`IAS_BATCH4_EVIDENCE`指向同一证据目录，保留原结果；只修改首次仍受限账号/本次新客户密码，不修改已有正常账号密码。测试秘密只存既有项目外受限的本角色文件，不在前端应用持久化。

gateway代理中断可能返回500/502；所有5xx与网络中断均按未确认处理，保持原键/原内容，不声称未提交。恢复始终由用户明确触发。真实中间开通故障点的RESUME/ENTER_INITIAL_PASSWORD浏览器覆盖属于合成HTTP；本角色真实浏览器验证的是完整开通及已完成回执丢失，不能冒称物理诱发全部中断状态。

## 第五批报修只读与文件

在现有入口增加`/tickets`、`/tickets/:id`，只限TENANT/EMPLOYEE且当前有显式`ticket.read`；不由管理员称谓或撤权能力推导权限。列表、原始报修、可信快照、提交时间线和私有附件只读，不提供受理/派工/维修完成操作。customer才有报修写入，公开契约见原登录契约15及任务拆分7。当前阶段只报告报修提交结果/待受理。

文件multipart上传和私有内容使用独立120秒预算，JSON调用继续沿原限制；浏览器下载有20MiB上限，预览前核对SHA256/大小/类型。每次预览/下载先以当前CSRF取得最长5分钟的应用授权，随后同源带Cookie取私有正文；不把授权当存储直链。身份/路由变化、组件卸载和授权到期撤销本地object URL；已下载文件不能追回。源文件/正文/联系人/CSRF不写Web Storage。下载/Range的服务端当前权限仍是最终依据。

`node scripts/batch5-ui-check.mjs`使用实际Chrome、合成HTTP和显式身份/生命周期夹具；不能标真实后端验收。`node scripts/batch5-real-check.mjs`读取第四批已开通客户及项目外既有受限密码，实际走5173/gateway/四服务/3307，包含已提交回执替换503、原UUID查询、未到后端404后显式原键重交、真实附件及撤权后≥60秒新Range拒绝。HTTP回执拦截不等于物理数据库故障。执行须先按沟通记录独占共享窗口；`IAS_BATCH5_EMPTY_ONLY=1`只跑空附件切片，完整验收不能只跑此开关。`IAS_BATCH5_EVIDENCE`指定本轮独立证据目录，避免覆盖旧轮。

完整文件恢复补验使用`node scripts/batch5-real-file-recovery.mjs`，需要同一完整切片及独占真实窗口。读取第四批已存在的同企业另一报修人，验证READY回执丢失仅原上传查询、真实重复ABORTED后的3文件显式删除与新UUID重新上传、JPEG/PDF客户/员工下载、同企业另一报修人及无能力/平台拒绝。仅创建本轮报修/文件及正常显式删除旧未引用文件，不改旧已完成样本、不重置账号。首次完整脚本会撤销一个原验收客户的旧会话，凭原密码可重新登录，密码来源不变。最终运行范围、未验范围及分轮结果见第五批前端运行证据摘要。


## ID-05：本次未观察到原上传

原上传GET返回404 TARGET_NOT_ACCESSIBLE时，只表示该次未观察到可访问记录，不能证明在途上传未执行。页面以NOT_OBSERVED显示提示，仅在原actor/submission/上传UUID/File对象/SHA256/设备与归属版本匹配、当前NORMAL客户及AUTH CSRF匹配attempt时，开放“显式原键同文件重传”；不会自动重发或丢弃附件。原上传参数在第一次发送前固定，仅保留内存；响应未知、忙或权限拒绝不替换原参数。401/403/503或其他非目标404不能授予该入口；后续读失败会撤销此前的未观察到恢复许可。重挂载仍可显式查询，刷新丢失File不伪造可重传状态。

`node scripts/id05-ui-check.mjs`用于精确红绿及合成回执/权限回归，`node scripts/id05-real-check.mjs`仅在窗口独占和默认最终四JAR就绪时运行，不带历史切片开关。后者使用新样本，验证首次POST送达前abort、真实404、同键同文件显式重传到READY/报修/下载，并以捕获的原始multipart受控迟到送达与页面重传并发，检查同一fileId及最终唯一附件引用。此为受控真实HTTP时序，不是物理TCP包延迟或数据库故障；秘密头/body只在内存，不入证据。证据目录可用IAS_ID05_EVIDENCE指定；既有失败样本不回改。


## 第六批授权队列与处理记录

沿原`/tickets`和`/tickets/:id`增加动作面板，不另造全量路由。客服须显式ticket.read且有当前企业scope；工程师须ticket.engineer.read且属于当前分派部门/本人，只能见本人待接单或处理中。菜单与allowedActions交集只是提示，列表/详情/候选/记录/动作/文件每次仍以服务端授权为准。分派部门仅取详情assignableDepartments，再查询实时合格候选，不硬编码企业/部门/工程师。

动作按当前expectedVersion/roundId/assignmentId与原小写UUID提交。未知时只查询原动作UUID；404仅本次未观察到，原参数仍在内存且当前身份/CSRF有效时才显式原键原参重交。刷新丢失参数只能查询，不能重建写入。409确定版本/轮次/分派/资格冲突关闭旧确认并刷新。新动作的成功、失败、finally均绑定身份代际、attempt、组件/路由、本操作和请求owner；旧回执不得清新pending或解锁新busy。

记录默认INTERNAL。CUSTOMER_VISIBLE须预览正文并确认；正文/工时/可见性修改后撤销原确认。客户只显示服务端公开投影，异常夹带内部记录/工时即停止显示，不靠隐藏字段代替授权。IN_PROGRESS明确显示仍在处理、尚无维修完成结果，受理人工说明与24×7响应/解决目标事实不冒称完整SLA能力。

`node scripts/batch6-ui-check.mjs`为真实Chrome/合成HTTP，含异常和迟到回执夹具，不是后端或数据库验收；IAS_BATCH6_EVIDENCE指定独立输出目录。`node scripts/batch6-real-check.mjs`只在共享窗口明确释放且默认最终四JAR就绪后运行，使用后端第六批非秘密账号/样本清单及项目外受限秘密，保留首次me更换Cookie→csrf顺序。真实API流程与受控HTTP回执故障分别标明；不运行环境初始化、SQL或修改旧正常密码。最终证据以本角色沟通记录及第六批前端运行证据为准，独立复核另行登记。

真实浏览器脚本默认直接读取后端既有第六批证据中的`账号部门与秘密引用.json`和`真实账号与样本.json`，不需要复制秘密或手写运行配置。后端已交付的三预留browser账号改密后，以项目外受限`frontend-browser-passwords.json`中同ref优先；其他账号仍读后端`batch6-current-passwords.json`，不能重置密码。可用`IAS_BATCH6_BROWSER_CONFIG`显式选择另一份非秘密样本映射。

`node scripts/batch6-real-scope.mjs`仅做真实读取/新下载授权拒绝，不改scope。默认读取主脚本`runtime/batch6-real/batch6-real-results.json`，自定义主证据目录时用`IAS_BATCH6_MAIN_RESULTS`指定该JSON；两脚本各用独立`IAS_BATCH6_EVIDENCE`输出目录。范围脚本使用同租户另一企业客服、同部门非指定工程师、跨部门工程师、无工单scope管理员、同企业另一报修人及跨租户客服/客户；拒绝页和原始HTTP状态分别检查。真实撤范围/停部门、≥60秒失权新Range及数据库停机不在此脚本通过范围，沿后端/独立验收的单列证据。重新执行主脚本会创建新报修，已改密账号沿当前密码重登，不能再标首次改密重新覆盖。

## 第七批普通维修收尾

沿既有工单详情增加解决表单、当前方案与不可变尝试历史；customer只允许当前原报修人的当前待确认方案决定。IN_PROGRESS提交方案→PENDING_CUSTOMER_CONFIRMATION；客户RETURN回同轮IN_PROGRESS、保留旧尝试/assignment/首响；下一次新solutionId；CONFIRM到RESOLVED，仍须授权客服close-check与人工说明确认才能CLOSED。不提供转派、补充、重开、评价或新处理附件。

工时仅读recordedWorkHours并确认，可跨日超过24小时；修改须先追加真实处理记录，方案快照不叠加旧方案。原因/方式/客户说明全部公开，提交前预览并确认，修改后原确认失效。完成时间转UTC毫秒精度，服务端核对本轮起点/接收时间，不能提前停止计时。证据仅可选当前工单已引用原报修附件（最多5），无新上传入口。

详情展示服务端timing中的首交时效、同轮累计待确认/最多72小时扣除、剩余额度和客户确认后冻结的最终有效耗时；客户端只格式化事实。待协调筛选使用coordination=CONFIRMATION_LIMIT_REACHED，GET不投递提醒、不自动确认或关闭。待关闭队列使用status=RESOLVED；工程师在待确认/已解决/已关闭保留当前本人只读范围。

收尾核验只支持ORDINARY_REPAIR_ONLY/NOT_ENABLED，明确这些子流程尚未启用，不伪造审批库存费用全部清零、已结算或无争议证明。旧版本/方案核验不能放行；POST仍须服务端锁内重验，人工ack不是服务端证明。

解决/客户决定/关闭复用原UUID及冻结参数、纯GET查询和显式同键恢复；旧方案决定不可套新方案。当前方案已更换时，旧未知决定的原UUID/参数仍保留同actor本标签页内存查询区，只读查询，不清成失败、不发到新方案；新方案须全新显式决定。刷新丢失原参数只能查原结果。success/catch/finally绑定身份generation/attempt、组件/路由、工单/round/assignment/solution/版本及owner，旧回执不能清新pending或busy。

第七批单元用例在tests/ticket-solution.spec.ts；实际Chrome合成脚本scripts/batch7-ui-check.mjs、batch7-callback-check.mjs，覆盖常规闭环/恢复及迟到矩阵。IAS_BATCH7_EVIDENCE指定独立输出，IAS_BATCH7_CASE可定向用例；合成身份/HTTP不是后端业务验收。浏览器单页串行并限制renderer/JS堆以降低共享Windows资源占用，仍需先核对自有监听和共享记录，失败轮次分别保留。最终默认四JAR真实联调与独立结论见本角色共享记录，不靠合成或截图宣布联合通过。


第七批真实Chrome使用`node scripts/batch7-real-check.mjs`，默认读取既有第六批非秘密browser账号/样本映射，密码以受限frontend-browser-passwords.json当前同ref优先；须等待后端新增两能力的明确CHANGE完成和默认最终四JAR交接。主轮输出batch7-real-results.json；可用IAS_BATCH7_EVIDENCE单独保存，IAS_BATCH7_BROWSER_CONFIG可选另一非秘密映射。主轮创建新工单并完成直接确认关闭和两次退回后的三方案关闭，覆盖三动作各原键503/404恢复，不修改旧已完成样本、密码/范围或环境。

`node scripts/batch7-real-scope.mjs`为另轮实际权限读取拒绝，主结果默认runtime/batch7-real/batch7-real-results.json，自定义时用IAS_BATCH7_MAIN_RESULTS指定；IAS_BATCH7_EVIDENCE选不同输出。覆盖既有七类无目标权限角色的当前详情/方案历史/收尾核验/记录/候选/原操作/附件新授权和列表计数，当前具资格的原assignment工程师关闭后只读/下载在主轮验证。真实停换部门/撤范围与≥60秒新Range、审计/锁竞争和计时受控边界属后端与独立验收单列证据，不以此脚本替代。

## 第八批补充资料与回复

按既有契约18（B8-1）增加WAITING_INFORMATION。staff授权客服在待受理、授权客服/当前工程师在处理中请求资料，公开问题需预览确认。customer仅服务端当前supplement-reply动作、NORMAL原报修人和快照企业当前成员可回复；客户端按钮只是提示，写前仍由服务端鉴权。回复只恢复PENDING_ACCEPTANCE或IN_PROGRESS，不等于受理、解决或新的轮次/分派。

详情用TicketSupplements展示当前问题及不可变公开请求/回复分页历史；TicketActions复用原UUID动作恢复。补充与方案确认各有独立72小时同轮累计额度，页面仅格式化服务端值，不自行暂停时钟。首交原始历时和扣补充后的有效耗时分显；首响历史等级不被后续受理调级覆盖。待补充队列用WAITING_INFORMATION，客服额度筛选为SUPPLEMENT_LIMIT_REACHED；GET不提醒、不推进。

仅客户回复允许本批新附件。ticket-supplement-work.ts保存内存ReplyDraft、原File/摘要、uploadId和replyOperationId，绑定actor/ticket/round/request/原assignment。SupplementFiles的multipart恰按replyOperationId、sha256、file顺序，不传设备ownershipVersion或新报修submissionId。最多5份PNG/JPEG/PDF，每份20MiB；后端最终格式校验。未知上传先查限定原目标；404只说明未观察到，显式同键同File重传，不自动发送。401/403/503不能开放重传。未知reply冻结正文/READY集合和原回复键，不能删除未知文件、自动换键或移到新问题。

回调包含身份generation/attempt、组件/路由、slot/owner、工单版本/round/assignment/current request检查，finish只释放原操作。新问题出现后旧回复只读查原UUID；明确409关闭旧确认刷新，新决策新键，旧原件仍保留只读查原上传。附件下载沿原授权代理、摘要校验和本地object URL清理，不开放通用处理附件。

验证命令：npm test、npm run build（两应用各执行）。scripts/batch8-ui-check.mjs为常规合成，batch8-callback-check.mjs为迟到回执/身份和重挂载矩阵，batch8-extra-check.mjs为上传竞争/409/双预算/移除补验；IAS_BATCH8_EVIDENCE选独立输出目录，IAS_BATCH8_CASE可筛前两脚本。合成Chrome不等于真实服务端通过。batch8-real-check.mjs使用默认最终四JAR与已有受限frontend-browser-passwords.json三个当前browser ref，不重置/改密码，不读取旧初始fallback；必须先有后端明确能力CHANGE/最终制品/共享窗口交接，不能在故障回归期间运行。真实联调执行状态、失败分轮和剩余边界以本角色共享记录及原位证据为准，不因脚本存在或构建通过宣称运行成功。

补充上传查询：UPLOADING/FAILED的size尚未生成时可为0/null、mediaType可为null，不按READY大小约束误拒绝；归属、原名和摘要仍严格检查。明确FAILED且服务端返回RETRY可显式同键同File重传，也可显式DELETE并等DELETING/DELETED回执后排除该文件；404仅未观察到，不能沿旧FAILED回执删除未知文件。scripts/batch8-upload-state-check.mjs验证这四个分支，仍是合成HTTP，不冒称真实元数据故障通过。

真实联调修复边界：已提交回复回执未知、重新核验后currentSupplement为空时，仍留在原UUID结果恢复区；只有出现不同请求/轮次才归档旧回复。客户DTO无需员工专属assignment对象，回复使用currentSupplement自身冻结的assignmentId，员工端仍交叉校验当前分派。模拟客户投影同步隐藏assignment/资格/候选部门，避免测试夹具误授字段；真实两轮失败及修复证据保留在本角色第八批目录。


## 第九批普通维修取消

原报修人按服务端allowedActions进行未受理直接取消或受理后申请；受理前补充已有首响不等于已受理。staff审核同时需要显式ticket.cancel.review、ticket.read与服务端当前企业范围。待审独立显示，不改变主状态、不停止工程师责任或计时；拒绝展示当前服务端状态，提交时历史状态仅供参照。

直接取消原因可选，申请/审核公开理由5～500码点；预览明确确认，修改后重新确认。未知保原UUID/完整参数，404仅本次未观察到，显式同键恢复；版本/当前申请变化或CANCELLED后旧操作仅查询，迟到回执与finally按身份代际/attempt、组件/路由及操作owner隔离。

取消后停止处理/补充/客户方案决定入口，终止历史不伪造回复或CONFIRM；历史、已引用附件按当前权限读取，未知附件仍保留。终结计时与两类扣除取服务端冻结事实，取消不计已解决。只支持普通维修本地原子取消，不宣称库存费用清理、外部取消、转派或重开。

契约19 B9-1为准：申请动作回执state=PENDING_REVIEW，申请cancellationState=PENDING_REVIEW；审核回执decision=APPROVE/REJECT，历史decision.decision=APPROVED/REJECTED/NO_LONGER_APPLICABLE；取消事实适用摘要为check.scope/relatedWorkflows。scripts/batch9-*在staff目录，构建/单元、全量拦截API的Chrome合成、默认最终四JAR真实联调及独立验收分层。真实脚本仅在共享记录明确交接最终制品/窗口后设置IAS_BATCH9_RUNTIME_CONFIRMED=1运行；脚本存在不代表已验。


第十批转派：staff新增独立有限收件 /transfers、客服发起/撤回、目标接受/拒绝及协调；customer仅已ACCEPTED公开交接。详情30秒纯GET核当前权限，隐藏时清正文/预览，失权清原草稿正文；源assignment/request/target/身份与组件归属隔离，未知结果保原UUID与冻结参数，只有显式查询/同键重交。缺record允许有限收件/拒绝，接受仍需三能力及服务端allowedActions。
本批已完成143单元、两应用类型检查/构建、合成恢复与回调及默认最终四JAR真实Chrome自验。真实两条交接处理至关闭，另四动作各ABORT/已提交503原键恢复、七身份范围拒绝及最终回读留证。入口为协作记录/阶段0/第十批前端运行证据/验证摘要.json；失败轮、测试助手中断和本角色12新样本索引分别保留。独立/联合验收仍待技术与测试顾问。真实脚本需显式IAS_BATCH10_RUNTIME_CONFIRMED及当前交付配置，窗口应先登记；脚本不启停服务、不配置权限或改密码。


第十一批重开：customer显式查询服务端窗口并申请；staff主管按独立源关闭审批范围核同故障/等级，可明确无目标或选择REOPEN用途候选。申请不建轮，批准才建新轮，指定工程师仍需新assignment本人接单；审批/分派/接单均不算人工首响。客服或当前已接单工程师的公开接手回复独立预览确认。历史路由 /tickets/:id/rounds/:roundId 只读，旧轮方案/决定/草稿/未知File不驱动新轮。已有公开READY引用需显式查询选择，不自动继承或新上传。
原UUID恢复绑定身份generation/attempt、组件、round/sourceClosure及原操作；旧回执仅历史查询，不自动重发。无assignment省略/null均兼容，其他投影守卫保持。契约21 B11-1及任务13为依据。batch11-ui/recovery/callback/projection/evidence/old-file-check为全API拦截Chrome合成；batch11-real-check/real-recovery/real-scope仅在最终四JAR、主管当前ref和窗口正式交付后设置IAS_BATCH11_RUNTIME_CONFIRMED=1及IAS_BATCH11_BROWSER_CONFIG运行，不初始化或改授权。当前166单元/两应用类型检查构建及合成通过；20:23后端交付后的默认四JAR真实Chrome三轮闭环5通过、三动作恢复首轮5通过1准备限流失败及最后一项定向补验通过、七身份范围7通过、最终只读回读修正轮7通过。原失败均保留；不拼为同轮全绿，不替代独立/联合验收。实际分层见共享记录及第十一批前端运行证据。
