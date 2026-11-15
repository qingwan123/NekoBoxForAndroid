package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();

  /**
   * 单个节点的测速，在 bg 进程里建独立实例跑（已连接代理时唯一能 protect socket 的路径）。
   * 返回握手延迟 ms，失败抛异常（异常文本会作为该节点的失败原因显示）。
   */
  int urlTestProfile(long profileId, String link, int timeout);
}
