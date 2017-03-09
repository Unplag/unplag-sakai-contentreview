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
unplag.checkType=1
```  

Possible check types (depends on your Unplag account plan):  
 - **0** = My Library
 - **1** = Internet
 - **2** = External DB
 - **4** = Internet + Library



#### 4. Enable content review for assignments in *tomcat/sakai/sakai.properties*
```ini
assignment.useContentReview=true
```  

If you have  more than one enabled provider, add to Site properties *(admin account -> sites -> Add / Edit Properties)*:
```
contentreview.provider:Unplag
```

## OPTIONAL PROPERTIES
```ini
unplag.poolSize=8
unplag.maxFileSize=20971520
unplag.allowAnyFileType=false
unplag.exclude.citations=true
unplag.exclude.references=true
```