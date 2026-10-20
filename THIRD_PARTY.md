# 来源、许可证与运行时

工作台基于 https://github.com/loks666/get_jobs 改造，保留get_jobs/LICENSE中的PolyForm Noncommercial 1.0.0和Required Notice。分发成品时许可证位于根目录LICENSE；分享、修改和分发须按适用许可进行，不将整个项目改标MIT。

语义分类方法参考 https://github.com/yeatssc/boss-auto-apply ，版本及MIT文本在get_jobs/third-party（成品为third-party）。

Windows成品附带由Eclipse Temurin JDK21生成的运行时，保留runtime/java/legal及NOTICE。源代码、构建及对应版本从 https://adoptium.net/ 和 https://github.com/adoptium/temurin21-binaries 获取；实际版本在runtime/java/release。请按该运行时GPLv2及Classpath Exception等许可要求分发。

Node版本在release-manifest.json，对应版本许可保存在runtime/node/LICENSE，对应源码为 https://github.com/nodejs/node 。

浏览器驱动来自Patchright/Playwright，完整包内LICENSE/NOTICE保留于driver/package；Java依赖许可随各依赖JAR保留。构建依赖见get_jobs/build.gradle.kts。未捆绑Chrome/Edge或Codex程序、用户会话和模型额度。

本文件是来源清单，不意味着已经完成所有分发法务核验。公开发布或商业使用前应复核所有适用许可证；上游非商业限制不得忽略。
