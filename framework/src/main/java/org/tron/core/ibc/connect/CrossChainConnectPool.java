package org.tron.core.ibc.connect;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.overlay.client.PeerClient;
import org.tron.common.overlay.discover.node.Node;
import org.tron.common.overlay.discover.node.NodeManager;
import org.tron.common.overlay.server.Channel;
import org.tron.common.utils.ByteArray;
import org.tron.core.config.args.Args;
import org.tron.core.net.peer.PeerConnection;

@Slf4j(topic = "net-cross")
@Component
public class CrossChainConnectPool {

  //key is chainId, value is connect list
  @Getter
  private Map<ByteString, List<PeerConnection>> crossChainConnectPool = new ConcurrentHashMap<>();

  private ScheduledExecutorService logExecutor = Executors.newSingleThreadScheduledExecutor();

  @Autowired
  private PeerClient peerClient;
  @Autowired
  private NodeManager nodeManager;

  public void init() {
    List<Node> nodeList = Args.getInstance().getCrossChainConnect();
    nodeList.forEach(n -> {
      peerClient.connectAsync(nodeManager.getNodeHandler(n), false, true);
    });

    logExecutor.scheduleAtFixedRate(() -> {
      try {
        logActivePeers();
      } catch (Throwable t) {
        logger.error("CrossChainConnectPool Exception in sync worker", t);
      }
    }, 30, 10, TimeUnit.SECONDS);
  }

  public void onConnect(ByteString chainId, Channel channel) {
    synchronized (this) {
      PeerConnection peerConnection = (PeerConnection) channel;
      if (!crossChainConnectPool.containsKey(chainId)) {
        crossChainConnectPool.put(chainId, new ArrayList<>());
      }
      if (!crossChainConnectPool.get(chainId).contains(peerConnection)) {
        crossChainConnectPool.get(chainId).add(peerConnection);
        //todo:sync the end block header

      }
    }
  }

  public void onDisconnect(Channel peer) {
    synchronized (this) {
      PeerConnection peerConnection = (PeerConnection) peer;
      for (ByteString key : crossChainConnectPool.keySet()) {
        if (crossChainConnectPool.get(key).contains(peerConnection)) {
          logger.info("disconnect the cross chain peer:{}", peer);
          crossChainConnectPool.get(key).remove(peerConnection);
          peerConnection.onDisconnect();
        }
      }
    }
  }

  public List<PeerConnection> getPeerConnect(ByteString chainId) {
    List<PeerConnection> peerConnectionList = crossChainConnectPool.get(chainId);
    return peerConnectionList == null ? Collections.emptyList() : peerConnectionList;
  }

  private void logActivePeers() {
    synchronized (this) {
      for (Entry<ByteString, List<PeerConnection>> entry : crossChainConnectPool.entrySet()) {
        String str = String
            .format("\n\n============ Cross Chain %s Peer stats: all %d\n\n",
                ByteArray.toHexString(entry.getKey().toByteArray()), entry.getValue().size());
        StringBuilder sb = new StringBuilder(str);
        for (PeerConnection peer : entry.getValue()) {
          sb.append(peer.log()).append('\n');
        }
        sb.append("===========================================================").append('\n');
        logger.info(sb.toString());
      }
    }
  }

}
