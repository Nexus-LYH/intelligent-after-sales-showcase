# gateway

Spring Cloud Gateway Server Web MVC路由入口，配套版本及启动方法见services/README.md。使用GatewayRouterFunctions和限时JDK HTTP传输，非任意URL转发器；公开方法/路径白名单，不开放/internal，无数据库。

剥除客户端内部/身份/转发头，向具体TLS对端注入独立调用边认证；保留所有Set-Cookie、清Cookie、错误状态与no-store。asset写入前由asset使用独立边再次核验当前身份与CSRF。入口限流是本机有界内存实现，不引入Redis。

ChainIntegrationTest启动本批自己持有的三个进程，使用真实3307及角色专属证书；结束只关闭自己进程。必须协调独占窗口，不能在前端真实联调中运行。最新交付证据位于协作记录/阶段0/identity第四批运行证据。
