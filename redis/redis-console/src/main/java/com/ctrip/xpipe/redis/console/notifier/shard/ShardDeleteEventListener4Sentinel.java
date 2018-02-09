package com.ctrip.xpipe.redis.console.notifier.shard;

import com.ctrip.xpipe.api.observer.Observable;
import com.ctrip.xpipe.api.pool.SimpleObjectPool;
import com.ctrip.xpipe.netty.commands.NettyClient;
import com.ctrip.xpipe.pool.XpipeNettyClientKeyedObjectPool;
import com.ctrip.xpipe.redis.console.notifier.EventType;
import com.ctrip.xpipe.redis.console.spring.ConsoleContextConfig;
import com.ctrip.xpipe.redis.core.protocal.cmd.AbstractSentinelCommand;
import com.ctrip.xpipe.redis.core.protocal.pojo.Sentinel;
import com.ctrip.xpipe.utils.IpUtils;
import com.ctrip.xpipe.utils.StringUtil;
import com.ctrip.xpipe.utils.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;

/**
 * @author chen.zhu
 * <p>
 * Feb 09, 2018
 */
@Component
public class ShardDeleteEventListener4Sentinel implements ShardEventListener {

    private static Logger logger = LoggerFactory.getLogger(ShardDeleteEventListener4Sentinel.class);

    @Autowired
    private XpipeNettyClientKeyedObjectPool keyedClientPool;

    @Resource(name = ConsoleContextConfig.REDIS_COMMAND_EXECUTOR)
    private ScheduledExecutorService scheduled;

    @Override
    public void update(Object args, Observable observable) {
        EventType type = (EventType) args;
        if(!(observable instanceof ShardDeleteEvent) || type != EventType.DELETE) {
            logger.info("[update] observable object not ShardDeleteEvent, skip. observable: {}, args: {}",
                    observable.getClass().getName(),
                    args.getClass().getName());
            return;
        }
        ShardDeleteEvent shardDeleteEvent = (ShardDeleteEvent) observable;

        removeSentinels(shardDeleteEvent);
    }

    @VisibleForTesting
    protected void removeSentinels(ShardDeleteEvent shardEvent) {

        String clusterId = shardEvent.getClusterName(), shardId = shardEvent.getShardName();

        String sentinelMonitorName = shardEvent.getShardMonitorName();

        String allSentinels = shardEvent.getShardSentinels();

        logger.info("[removeSentinels]removeSentinel cluster:{}, shard:{}, masterName:{}, sentinelAddress:{}",
                clusterId, shardId, sentinelMonitorName, allSentinels);

        if(checkEmpty(sentinelMonitorName, allSentinels)) {
            return;
        }

        List<InetSocketAddress> sentinels = IpUtils.parse(allSentinels);
        List<Sentinel> realSentinels = getRealSentinels(sentinels, sentinelMonitorName);
        if(realSentinels == null) {
            logger.warn("[removeSentinels]get real sentinels null");
            return;
        }

        logger.info("[removeSentinels]removeSentinel realSentinels: {}", realSentinels);

        for(Sentinel sentinel : realSentinels) {

            removeSentinel(sentinel, sentinelMonitorName);
        }
    }

    @VisibleForTesting
    protected void removeSentinel(Sentinel sentinel, String sentinelMonitorName) {
        SimpleObjectPool<NettyClient> clientPool = keyedClientPool
                .getKeyPool(new InetSocketAddress(sentinel.getIp(), sentinel.getPort()));

        AbstractSentinelCommand.SentinelRemove sentinelRemove = new AbstractSentinelCommand
                .SentinelRemove(clientPool, sentinelMonitorName, scheduled);
        try {
            String result = sentinelRemove.execute().get();
            logger.info("[removeSentinels]removeSentinel {} from {} : {}", sentinelMonitorName, sentinel, result);

        } catch (InterruptedException | ExecutionException e) {
            logger.error("removeSentinel {} from {} : {}", sentinelMonitorName, sentinel, e.getMessage());
        }
    }

    private boolean checkEmpty(String sentinelMonitorName, String allSentinels) {

        if(StringUtil.isEmpty(sentinelMonitorName)){
            logger.warn("[checkEmpty]sentinelMonitorName empty, exit!");
            return true;
        }

        if(StringUtil.isEmpty(allSentinels)){
            logger.warn("[checkEmpty]allSentinels empty, exit!");
            return true;
        }
        return false;
    }

    @VisibleForTesting
    protected List<Sentinel> getRealSentinels(List<InetSocketAddress> sentinels, String sentinelMonitorName) {

        List<Sentinel> realSentinels = null;

        for(InetSocketAddress sentinelAddress: sentinels){

            SimpleObjectPool<NettyClient> clientPool = keyedClientPool.getKeyPool(sentinelAddress);
            AbstractSentinelCommand.Sentinels sentinelsCommand = new AbstractSentinelCommand
                    .Sentinels(clientPool, sentinelMonitorName, scheduled);

            try {
                realSentinels = sentinelsCommand.execute().get();
                logger.info("[getRealSentinels]get sentinels from {} : {}", sentinelAddress, realSentinels);
                if(realSentinels.size() > 0){
                    realSentinels.add(
                            new Sentinel(sentinelAddress.toString(),
                                    sentinelAddress.getHostString(),
                                    sentinelAddress.getPort()
                            ));
                    break;
                }
            } catch (InterruptedException | ExecutionException e) {
                logger.warn("[getRealSentinels]get sentinels from " + sentinelAddress, e);
            }
        }


        return realSentinels;
    }

    @VisibleForTesting
    public ShardDeleteEventListener4Sentinel setScheduled(ScheduledExecutorService scheduled) {
        this.scheduled = scheduled;
        return this;
    }

    @VisibleForTesting

    public ShardDeleteEventListener4Sentinel setKeyedClientPool(XpipeNettyClientKeyedObjectPool keyedClientPool) {
        this.keyedClientPool = keyedClientPool;
        return this;
    }
}