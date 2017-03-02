# Sakai ContentReview Unplag plugin

**Supported Sakai versions:** 11.x

Author: Ben Larson <developer@unplag.com>  
Site: https://unplag.com

INSTALL  
==============

#### 1. Build plugin and deploy
```bash
cd SAKAI_SRC_HOME
mvn install
cd SAKAI_SRC_HOME/content-review
git clone https://github.com/Unplag/unplag-sakai-contentreview.git contentreview-impl-unplag
cd contentreview-impl-unplag
mvn clean install sakai:deploy -Dmaven.tomcat.home=/path/to/your/tomcat
```  

#### 2. Add Unplag to contentReviewProviders list:
Open */tomcat/components/sakai-content-review-pack-federated/WEB-INF/components.xml* and add:
```xml
<util:list id="contentReviewProviders">            
    <ref bean="org.sakaiproject.contentreview.service.ContentReviewServiceUnplag"/>       
</util:list>
```  

#### 3. Add settings to *tomcat/sakai/sakai.properties*
```ini
# Unplag settings
unplag.key=<your-key>
unplag.secret=<your-secret>
unplag.pool.size=8
# 0 - MY_LIBRARY
# 1 - WEB
# 2 - EXTERNAL_DATABASE
# 4 - WEB_AND_MY_LIBRARY
unplag.check.type=1
```  

#### 4. Enable content review for assignments in *tomcat/sakai/sakai.properties*
```ini
assignment.useContentReview=true
```  

If you have  more than one enabled provider, add to Site properties *(admin account -> sites -> Add / Edit Properties)*:
```
contentreview.provider:Unplag
```