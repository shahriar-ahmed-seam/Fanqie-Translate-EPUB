# The engine library looks these classes and members up by name over JNI.
# (wry 0.57.1 and tauri 2.12.5 rules for package com.pofl.fanqienoveldownloader)
-keep class com.pofl.fanqienoveldownloader.* {
  native <methods>;
}
-keep class com.pofl.fanqienoveldownloader.WryActivity {
  public <init>(...);
  void setWebView(com.pofl.fanqienoveldownloader.RustWebView);
  java.lang.Class getAppClass(...);
  int getId();
  java.lang.String getVersion();
  int startActivity(...);
}
-keep class com.pofl.fanqienoveldownloader.Ipc {
  public <init>(...);
  @android.webkit.JavascriptInterface public <methods>;
}
-keep class com.pofl.fanqienoveldownloader.RustWebView {
  public <init>(...);
  void loadUrlMainThread(...);
  void loadHTMLMainThread(...);
  void evalScript(...);
}
-keep class com.pofl.fanqienoveldownloader.RustWebChromeClient,com.pofl.fanqienoveldownloader.RustWebViewClient {
  public <init>(...);
}
-keep class com.pofl.fanqienoveldownloader.TauriActivity {
  public app.tauri.plugin.PluginManager getPluginManager();
}
# Plugins are created by class name and their commands are found by reflection.
-keep class app.tauri.** { *; }
-keep class com.pofl.fanqienoveldownloader.FileOpenerPlugin { *; }
-keep class com.pofl.fanqienoveldownloader.NativeTtsPlugin { *; }
-keep class com.pofl.fanqienoveldownloader.ListenMediaPlugin { *; }
-keep class com.pofl.fanqie.accountauth.AccountAuthPlugin { *; }
-keep class com.example.tomato.engine.EngineLibrary { *; }
-keepclassmembers class com.example.tomato.engine.EngineBridge$Host {
  @android.webkit.JavascriptInterface public <methods>;
}
