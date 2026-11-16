package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.osgi.BundleUtils;
import org.jahia.services.scheduler.BackgroundJob;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Jahia background job of an alternative-text run: {@link AltTextJobServiceImpl#runBatch} does the work.
 *
 * <p>{@link BackgroundJob} runs it as the user of the run. Quartz creates it from its class name, so it reaches the
 * module's service through OSGi.</p>
 */
public class AltTextJob extends BackgroundJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AltTextJob.class);

    @Override
    public void executeJahiaJob(JobExecutionContext context) {
        AltTextJobServiceImpl jobs = BundleUtils.getOsgiService(AltTextJobServiceImpl.class, null);
        if (jobs == null) {
            LOGGER.warn("Alternative text run {} stops: the module service is not started",
                    context.getJobDetail().getJobDataMap().getString(AltTextJobServiceImpl.RUN));
            return;
        }
        jobs.runBatch(context.getJobDetail().getJobDataMap());
    }
}
