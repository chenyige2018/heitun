# 豚链 APP（自主打包，WebView 壳）

不依赖任何第三方打包平台：源码在你手里，GitHub Actions 云端编译并签名，产出 APK 由你自己分发。

## 一、怎么拿到 APK

1. 把本目录推到 GitHub 仓库（私有即可）
2. 打开仓库 → **Actions** → `Build Android APK` → 绿色对勾后点进去
3. 页面底部 **Artifacts** 下载：
   - `tunlian-release-apk` → `app-release.apk`（直接给用户安装）
   - `tunlian-release-keystore` → `keystore.jks`（**务必保存**，后面讲为什么）

推送任何一次 commit 都会自动重新构建。

## 二、签名文件为什么必须保存

首次构建时工作流会**自动生成** `keystore.jks`。如果下次构建时它丢了，会生成一个新的 —— 签名不同，用户安装新版本时会被要求"卸载旧版"，无法直接覆盖升级。

正确做法（二选一）：

- **简单**：把构建产物里的 `keystore.jks` 下载保存好，以后本地构建时放回 `app/keystore.jks`
- **规范**：把 `keystore.jks` 转成 base64 存到仓库 **Settings → Secrets → Actions → `KEYSTORE_BASE64`**，之后每次云端构建都复用同一签名

## 三、常用修改

| 想改什么 | 改哪里 |
| --- | --- |
| 打开的网址 | `app/src/main/res/values/strings.xml` 的 `site_url` |
| 应用名 | 同上文件的 `app_name` |
| 版本号 | `app/build.gradle` 的 `versionCode` / `versionName`（升版本必须同时改 versionCode） |
| 主题色 | `app/src/main/res/values/colors.xml` |
| 图标 | `app/src/main/res/drawable/ic_launcher_foreground.xml`（矢量图，改 `pathData` 即可换 LOGO） |
| 包名 | `app/build.gradle` 的 `applicationId`（**改了之后签名不变但被视为新应用，别随便改**） |

## 四、这个壳比市面套壳平台多做了什么

- **网页上传图片可用**：支持相册选图、文件选择、直接拍照（会员发文章必须用到）
- **网页下载可用**：APK / 图片 / 文件交给系统下载管理器，通知栏可见（市面大多数壳点了下载没反应）
- **站外链接用系统浏览器打开**：不会让用户在你的壳里跑到别人的网站
- **证书异常一律拦截**：防止中间人劫持
- **UA 追加 `TunLianApp/版本号`**：后端可据此识别 APP 来源
- 返回键网页回退、连按两次退出、断网重试页

## 五、本地构建（可选）

需要 JDK 17 + Android SDK，且 `app/keystore.jks` 存在：

```
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`
