# 实习投递工作台 · Windows朋友试用版

版本：2.1.0-preview.1。四个平台：BOSS直聘、实习僧、智联招聘、应届生实习。最多三个平台并行，独立登录、限额和暂停，按简历匹配度排序，平台内北京时间当日去重。

这是独立发布副本，个人资料与求职偏好为空，演示采用虚构数据。未包含作者的个人简历、账号、投递记录、API密钥或定时授权。当前属于小范围试用，尚不承诺所有Windows电脑和招聘页面都可用。公开分发与本地数据的边界见[隐私说明](PRIVACY.md)。

## 普通使用者

在GitHub Release附件中下载`InternshipWorkbench-2.1.0-preview.1-windows-x64.zip`，不要把“Source code.zip”当安装包。完整解压到可写目录，然后双击“打开工作台.cmd”。首次弹出模型配置，随后在网页完成自己的资料与平台配置。无需安装Java、Gradle或Node，不需要作者的旧版项目目录。

本包包含Java运行时、Node和浏览器驱动；仍需本机已有Chrome或Edge。使用Codex模式需要自行安装并登录自己的Codex；使用API模式无需Codex账号，配置自己的API地址、模型和密钥。程序不提供共享算力，不附带作者账号。

初始每日定时、各平台自动投递、简历就绪、待确认自动放行都关闭。先验证只筛选，再人工确认单条投递；每个平台取得自己的成功回执后才允许自动投递。不要在不了解偏好的情况下开启自动确认待确认岗位。

详见[使用说明](使用说明.md)。源码和成品包都应保留[上游许可证](LICENSE)及第三方声明；本项目含非商业用途限制，不以MIT覆盖上游许可。

## 开发与打包

源码依赖Java21和Gradle Wrapper。浏览器相关测试需指定`-PtestBrowser`和`-PtestDriver`；没有浏览器时跳过的测试不能算真实网页验证。

构建：在get_jobs目录执行`gradlew.bat test bootJar -x buildFrontend`。独立工作台是原生静态页面，无需旧Next.js管理中心。构建驱动任务需要npm/pnpm。

打包：`python tools/build_portable.py --java-home <JDK21目录> --node <node.exe> --driver <包含package/cli.js的目录> --output <新的输出目录>`。打包器只装配明确列出的程序与许可证，不复制开发数据库、浏览器会话、日志或作者配置，输出ZIP和SHA256。Node许可证从对应版本的官方源码取回。

提交前执行`python tools/audit_release.py .`，发布包生成时也执行路径与秘密模式检查。可通过`--private-patterns <私有JSON文件>`补充本人标识的正则表达式列表；该文件必须放在发布范围之外，姓名或路径即使转成字符编码也不能写进公开检查脚本。发布前还要检查待推送的Git历史、源码ZIP和成品ZIP中的内嵌JAR及驱动文件。检查不是完整秘密审计的替代，首次公开前仍需人工复核及独立电脑试用。

## 来源

基于loks666/get_jobs改造；岗位语义筛选参考yeatssc/boss-auto-apply。见[第三方说明](THIRD_PARTY.md)及get_jobs/third-party。招聘网页变化或验证要求可能导致暂停，需使用者自己处理，不包含验证码绕过功能。
