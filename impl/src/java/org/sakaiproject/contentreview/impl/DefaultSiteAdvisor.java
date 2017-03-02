package org.sakaiproject.contentreview.impl;

import org.sakaiproject.contentreview.service.ContentReviewSiteAdvisor;
import org.sakaiproject.site.api.Site;

public class DefaultSiteAdvisor implements ContentReviewSiteAdvisor {

    @Override
    public boolean siteCanUseReviewService(Site site) {
        return true;
    }
}
