package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.osgi.BundleUtils;
import org.jahia.services.scheduler.BackgroundJob;
import org.quartz.JobExecutionContext;

/**
 * One Jahia background job of an alternative-text run: {@link AltTextJobServiceImpl#runBatch} does the work.
 *
 * <p>{@link BackgroundJob} runs it as the user of the run. Quartz creates it from its class name, so it reaches the
 * module's service through OSGi. While that service is not started, such as during a restart of genai-connector,
 * the job postpones its work. A job that fires while this module is stopped is lost: Jahia then runs a job that
 * does nothing in its place.</p>
 */
public class AltTextJob extends BackgroundJob {

    @Override
    public void executeJahiaJob(JobExecutionContext context) {
        AltTextJobServiceImpl jobs = BundleUtils.getOsgiService(AltTextJobServiceImpl.class, null);
        if (jobs == null) {
            AltTextJobServiceImpl.postpone(context.getJobDetail().getJobDataMap());
            return;
        }
        jobs.runBatch(context.getJobDetail().getJobDataMap());
    }
}
