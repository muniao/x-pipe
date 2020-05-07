package com.ctrip.xpipe.redis.console.election;

import com.ctrip.xpipe.api.foundation.FoundationService;
import com.ctrip.xpipe.redis.console.dao.ConfigDao;
import com.ctrip.xpipe.redis.console.model.ConfigModel;
import com.ctrip.xpipe.redis.console.model.ConfigTbl;
import com.ctrip.xpipe.redis.console.resources.MetaCache;
import com.ctrip.xpipe.redis.core.entity.ClusterMeta;
import com.ctrip.xpipe.redis.core.entity.DcMeta;
import com.ctrip.xpipe.redis.core.entity.XpipeMeta;
import com.ctrip.xpipe.utils.DateTimeUtils;
import com.ctrip.xpipe.utils.VisibleForTesting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.unidal.dal.jdbc.DalException;

import java.util.Date;

@Component
public class CrossDcLeaderElectionAction extends AbstractPeriodicElectionAction {

    public static final String KEY_LEASE_CONFIG = "LEASE";

    public static final String SUB_KEY_CROSS_DC_LEADER = "CROSS_DC_LEADER";

    protected static int MAX_ELECTION_DELAY_MILLISECOND = 30 * 1000;

    protected static int ELECTION_INTERVAL_SECOND = 10 * 60;

    protected static int MAX_ELECT_RETRY_TIME = 3;

    protected String dataCenter = FoundationService.DEFAULT.getDataCenter();

    protected String localIp = FoundationService.DEFAULT.getLocalIp();

    @Autowired
    private ConfigDao configDao;

    @Autowired
    private MetaCache metaCache;

    private ConfigTbl currentConfig;

    @Override
    protected void doElect() {
        int retryTime = 0;
        ConfigModel model = buildDefaultConfig();

        while (retryTime < MAX_ELECT_RETRY_TIME) {
            retryTime++;

            try {
                logger.debug("[doElect] dc {} try to elect self to cross dc leader", dataCenter);
                configDao.updateConfigIdempotent(model,
                        DateTimeUtils.getSecondsLaterThan(new Date(), ELECTION_INTERVAL_SECOND),
                        currentConfig.getDataChangeLastTime());
            } catch (Exception e) {
                logger.info("[doElect] elect self fail, {}", e.getMessage());
            }

            try {
                refreshConfig();
                if (isConfigActive()) {
                    logger.info("[doElect] new lease take effect, cross dc leader {}", currentConfig.getValue());
                    return;
                }
            } catch (Exception e) {
                logger.info("[doElect] refresh config fail, {}", e.getMessage());
            }
        }

        logger.info("[doElect][fail] retry {} times", retryTime);
    }

    @Override
    protected boolean shouldElect() {
        try {
            refreshConfig();
        } catch (Exception e) {
            logger.info("[shouldElect] get master dc lease fail {}", e.getMessage());
        }

        if (null == currentConfig) {
            try {
                configDao.insertConfig(buildDefaultConfig(), new Date(), "lease for cross dc leader");
                refreshConfig();
            } catch (Exception e) {
                logger.info("[shouldElect] create and get master dc lease fail {}", e.getMessage());
            }
        }

        return isConfigExpired();
    }

    @Override
    protected void beforeElect() {
        long delay = calculateElectDelay();
        logger.debug("[beforeElect] sleep for {}", delay);
        try {
            if (delay > 0) Thread.sleep(delay);
        } catch (Exception e) {
            logger.info("[beforeElect] wait for {} fail {}", delay, e.getMessage());
        }
    }

    @Override
    protected void afterElect() {
        logger.debug("[afterElect] current config {}", currentConfig);
        if (isConfigActive()) notifyObservers(currentConfig.getValue());
        else if (isConfigExpired()) notifyObservers(null);
    }

    @Override
    protected long getElectIntervalMillSecond() {
        if (isConfigActive()) return currentConfig.getUntil().getTime() - (new Date()).getTime();
        else if (isConfigExpired()) return 0;
        else return ELECTION_INTERVAL_SECOND * 1000L;
    }

    @Override
    protected String getElectionName() {
        return "CrossDcLeaderElection";
    }

    private ConfigModel buildDefaultConfig() {
        ConfigModel config = new ConfigModel();
        config.setKey(KEY_LEASE_CONFIG)
                .setSubKey(SUB_KEY_CROSS_DC_LEADER)
                .setVal(dataCenter)
                .setUpdateIP(localIp)
                .setUpdateUser(dataCenter + "-DcLeader");

        return config;
    }

    private long calculateElectDelay() {
        return (long) (calculateActiveClusterRatio() * MAX_ELECTION_DELAY_MILLISECOND);
    }

    private float calculateActiveClusterRatio() {
        XpipeMeta xpipeMeta = metaCache.getXpipeMeta();
        long count;
        long totalCluster = 0;
        long activeClusterCount = 0;

        for (DcMeta dcMeta : xpipeMeta.getDcs().values()) {
            count = 0;
            for (ClusterMeta clusterMeta : dcMeta.getClusters().values()) {
                if (!clusterMeta.getActiveDc().equals(dcMeta.getId())) {
                    continue;
                }

                count++;
            }

            if (dcMeta.getId().equalsIgnoreCase(dataCenter)) {
                activeClusterCount += count;
            }
            totalCluster += count;
        }

        if (0 == totalCluster) return 0;
        return activeClusterCount / (totalCluster * 1f);
    }

    private boolean isConfigExpired() {
        return null != currentConfig && (new Date()).compareTo(currentConfig.getUntil()) >= 0;
    }

    private boolean isConfigActive() {
        return null != currentConfig && (new Date()).compareTo(currentConfig.getUntil()) < 0;
    }

    private void refreshConfig() throws DalException {
        try {
            currentConfig = configDao.getByKeyAndSubId(KEY_LEASE_CONFIG, SUB_KEY_CROSS_DC_LEADER);
        } catch (Exception e) {
            currentConfig = null;
            throw e;
        }
    }

    @VisibleForTesting
    protected void setConfigDao(ConfigDao configDao) {
        this.configDao = configDao;
    }

    @VisibleForTesting
    protected void setMetaCache(MetaCache metaCache) {
        this.metaCache = metaCache;
    }
}