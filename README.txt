学习用途与免责声明
**学习用途与免责声明：下载、安装或使用前请先阅读。**

本项目用于 Android 电视应用开发、网络播放、遥控器适配与音视频解码的学习和技术交流。本项目不托管影视或广播视频；代码开源、地址公开或检测通过，不代表已取得相关内容的传播授权。请仅访问自己有权使用的内容，遵守适用法律、来源服务条款及第三方许可，不得用于侵犯版权或其他权益的用途。

第三方网页、播放地址及评分数据可能变化；不保证全部内容可播放、长期可用或兼容所有电视。安装、网络调试和自行配置应由设备所有者了解后操作。本声明不免除任何依法应承担的责任。如发现权利或安全问题，请通过本仓库 Issues 提供具体链接和说明，以便核查处理。

完整电脑 AI 联网安装与 U 盘安装步骤见 INSTALL.md；项目图文说明见 README.md。

千秋动画 1.2.0

独立的 Android 电视动漫搜索与播放器；应用包名 com.qianq.kandongman。
目标设备：微鲸 WTV55K1J，Android 5.0.2 / API 21。
最低 Android 5.0，电视版包含 armeabi-v7a，适合当前电视的32位应用运行环境。

操作
首页默认热门：90个与目录匹配的豆瓣高分作品，按评分降序；每条显示实际评分作品/季名，快照日期2026-10-22。
三个Tab为热门、全部动漫、历史；“全部动漫”保留431项基础索引与链接过滤后的可见目录，支持A–Z / #。选字母会清空关键词。
输入中文、别名、英文原名或每个字的拼音首字母，立即筛选目录。
支持大小写、空格和全角字母，例如mztkn、MZ TKN、hzw、xmyrz。
选中目录条目后，使用完整中文名称查询当前来源；缩写重名时保留候选。
未收录的作品可输入完整名称后按搜索，直接查询来源。右上方可切换来源。
基础目录431个名称。启动时使用批量网络检查结果隐藏全来源无搜索结果项，并采用已验证的来源别名搜索词。
目录为发现索引；搜索、详情和抽查链接的HTTP检查不保证每一集都可播放。
“目录”按钮清空筛选并回到完整目录；返回键恢复上一层目录的关键词和字母。
搜索结果 → 播放线路 → 分集 → 播放。
“继续观看”恢复上次剧集和播放位置。
播放时：确定/菜单显示控制栏；收起后左右快退/快进10秒。
控制栏支持暂停、上一集、下一集、选集、解码选择、重试和进度条。
返回键回到选集页面；离开播放器时停止播放并保存进度。

来源
内置 MX动漫、Aki动漫、稀饭动漫三个规则，读取的是公开网页。
来源规则基于 KazumiRules（MIT），原始 JSON 保存在 assets/。
https://github.com/Predidit/KazumiRules
采用独立 Java/XPath 实现，没有复制 Kazumi 的 Dart/GPL 应用代码。
本应用在电视本机完成请求、解析和播放，不需要电脑服务常驻。
部分页面还需 WebView 执行普通播放器脚本；解析有超时和返回操作。
来源网页变化、线路超时或服务器拒绝访问时，请换线路或来源。
没有账号登录、DRM或付费鉴权处理；没有关闭TLS证书检查。
应用内补充GTS Root R1～R4、ISRG Root X1和USERTrust RSA六张公开CA根证书，修复Android 5根证书库过旧的问题。
原始证书来自 https://pki.goog/roots.pem 与 https://letsencrypt.org/certs/isrgrootx1.pem。
请求仍验证证书链、有效期和主机名；不修改电视的全局证书库。

播放
VideoLAN LibVLC 3.6.5；自动/仅硬解/仅软解三种策略。
自动模式发生播放错误或25秒无视频时，只重试一次软解。
优先选择可用线路；软解不会修复网络或权限错误。
播放历史保存在本机应用私有目录。

构建
JDK21、PowerShell7、Android SDK platform35、build-tools35.0.0。
运行 pwsh -File build.ps1
输出 dist/KanDongman-1.2.0.apk。
LibVLC AAR SHA256固定在构建脚本中。
jsoup固定为1.13.1，使用其Java7时代的HTML/DOM实现以适配API21。
测试依赖 json-test-only.jar 只用于桌面测试，不打入APK。
签名密钥 anime-local.jks 仅供本地版本更新，未包含在源码压缩包中。

验证
tests/AnimeCatalogTest.java：431个目录名称、初始字母、别名、歧义和A–Z筛选测试。
tests/SourcesTest.java：三个来源的真实HTML样本、线路分集、视频地址提取和URL检查。
tests/TlsTest.java：补充公开根证书后，仍拒绝不受信任的自签名服务器证书。
网络样本报告：verification/network/。
完整电视验证结果见 VERIFICATION.txt。

许可证
应用源码 MIT。第三方说明见 THIRD_PARTY.txt 和 licenses/。

链接有效性与自动清理（1.1.1）
verification/audit_catalog.py 在电脑检查431个名称、三个来源的搜索、最多三个详情结果、最多两条线路的首集页面；能提取到的媒体地址使用小范围GET检查。
对搜不到的常见名称，finalize_catalog_audit.py 按站点别名和明确标题片段复查，避免误删命名不同的作品。
所有来源都正常返回、原名及已测试别名均无结果的名称，从默认目录隐藏；仍可手动输入完整名称搜索。
HTTP404/410必须连续确认两次才过滤；401/403/429/5xx、TLS错误和超时都不作为永久失效证据。
实际使用时继续检查新遇到的详情/播放地址，已确认失效的搜索结果或剧集会从列表移除。
预检使用Range bytes=0-0，不下载完整视频；临时网络问题交由播放器继续尝试。
失效记录与目录不可用快照保留7天，避免上游恢复后长期被旧结论隐藏；验证过的搜索别名继续保留。
检查报告：verification/catalog-audit/summary.json、results.json、requests.json。
注意：HTTP200不是完整播放证明；没有逐集/全片/全线路验证，也没有验证所有HLS分片。
重新检查：py -3 verification/audit_catalog.py，完成后运行 py -3 verification/finalize_catalog_audit.py，再构建。
检查脚本依赖requests和lxml。支持--resume恢复已完成项目并补测媒体入口。
新增测试：tests/LinkHealthTest.java、tests/AuditCatalogTest.py。
打包源码及校验和：py -3 package_release.py；签名私钥不进入源码压缩包。

1.2.0界面与观看历史
应用名称与中文安装包名称：千秋动画；包名和签名保持不变，覆盖升级保留原记录。
焦点：亮黄色#FFE082、深色文字、白色3dp边框；按下为#FFC83D；当前激活标签紫色#6352C7，未选按钮#2D344C。
主列表和选集/解码列表使用紫色底、黄色边框的焦点；输入框与单选控件使用黄色强调色。
历史记录最近20部作品（并设置存储体积上限），含完整分集列表和当前剧集/进度。旧版最后一次观看自动迁移。
历史本体和进度分开保存，播放进度约每10秒及退出播放器时记录；历史按最近观看时间排序。
豆瓣评分是有日期的公开数据快照，不是实时热度榜，也不是全量豆瓣排名。同系列仅保留一个最高分条目，显示具体作品/季名；点击后检索对应系列的播放资源。
评分数据和官方URL：assets/douban-popular.json；采集原始响应：verification/douban/。
刷新数据：py -3 verification/fetch_popular.py；应用内菜单→评分来源可查看说明和官方URL。
新增测试：WatchHistoryTest、PopularDataTest；电视实测见VERIFICATION.txt。
