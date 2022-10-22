## 此项目是干什么的？

黑马点评是一个不错的学习redis的项目，包含了redis中绝大多数的使用场景，内容丰富

B站视频地址：https://www.bilibili.com/video/BV1cr4y1671t/?spm_id_from=333.999.0.0&vd_source=b3e4d8808f74672941df0b800eebcee3

## 如何运行此项目？

1、先找到sql文件，在数据库中执行

2、开启Nginx，打开nginx目录，cmd `start nginx.exe`

3、开启redis，由于此项目使用了最新的GeoSearch，因此redis版本需要在6.2以上

4、访问localhost:8080即可进入网站首页

### 如何安装6.2以上版本的redis

redis在5.0以上就不再维护window版本了，因此需要一个linux虚拟机

只讲一下大致的思路，具体的安装步骤网上都有：

1、下载VMWare + CentOs镜像，配好虚拟机的设置

2、linux虚拟机和windows通过telnet服务进行，也就是说linux需要安装telnet的服务器、window开启telnet的客户端（可以都安装服务器、客户端，用于测试单机的telnet是否可用）

3、安装成功后，需要设置防火墙的端口，将linux的23端口开放，23为本机使用的telnet端口

4、将linux的6379端口也开放，6379为redis的端口

通过windows上的RDM测试连接  ==虚拟机ip:6379== 看redis是否可用

