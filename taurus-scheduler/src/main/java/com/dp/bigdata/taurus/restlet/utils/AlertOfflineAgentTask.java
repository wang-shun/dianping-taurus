package com.dp.bigdata.taurus.restlet.utils;

import com.dianping.cat.Cat;
import com.dianping.lion.EnvZooKeeperConfig;
import com.dianping.lion.client.ConfigCache;
import com.dianping.lion.client.LionException;
import com.dianping.ops.http.HttpPoster;
import com.dp.bigdata.taurus.core.MailHelper;
import com.dp.bigdata.taurus.restlet.resource.IAllHosts;
import com.dp.bigdata.taurus.restlet.resource.IExceptionHosts;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.restlet.data.MediaType;
import org.restlet.resource.ClientResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.TimerTask;

import jodd.util.StringUtil;

/**
 * Created by kirinli on 15/1/30.
 */
public class AlertOfflineAgentTask  extends TimerTask {
    private final static String restlet_url_base = new String("http://localhost:8192/api/").intern();

    public void run() {
        // 监测异常的Agent 告警
        ClientResource cr = new ClientResource(restlet_url_base + "exceptionhosts");
        IExceptionHosts hostsResource = cr.wrap(IExceptionHosts.class);
        cr.accept(MediaType.APPLICATION_XML);
        String exceptionHosts = hostsResource.retrieve();
        
        // 检测正常Agent 发现异常 告警
        cr = new ClientResource(restlet_url_base + "allhosts");
        IAllHosts allOnlineHostsResource = cr.wrap(IAllHosts.class);
        cr.accept(MediaType.APPLICATION_XML);
        String onlineHostsJsonStr = allOnlineHostsResource.retrieve();
        
        String domain = null;
        String reportToOps = null;
        try {
            domain = ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress()).getProperty("taurus.web.serverName");
            reportToOps = ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress()).getProperty("taurus.agent.down.ops.report.alarm.post");
        } catch (LionException e) {
            domain="http://taurus.dp";
            reportToOps = "http://pulse.dp/report/alarm/post";
            e.printStackTrace();
        }
        
        
        // 检测异常agent:isOnline = 1 and isConnected = 0
        if (StringUtils.isNotBlank(exceptionHosts)){
            String[] hostLists = exceptionHosts.split(",");

            for (String host: hostLists){

                String context = "您好，taurus-agent的job主机 ["
                        + host
                        + "] 心跳异常请在【"+domain+"/host_center】核实" +
                        "监控连接如下："+domain+"/hosts?hostName="
                        + host
                        +"，谢谢~";
                String exceptContext = "您好，taurus-agent的job主机 ["
                        + host
                        + "] 服务已经挂掉请在【"+domain+"/host_center】核实并重启该Job机器的TOMCAT" +
                        "监控连接如下："+domain+"/hosts?hostName="
                        + host
                        +"，谢谢~";


                try {


                    String url1= "http://"+host+":8080/agentrest.do?action=isnew";
                    String url2 = "http://"+host+":8088/agentrest.do?action=isnew";
                    String isAlive1 = get_data(url1);
                    String isAlive2 = get_data(url2);

                    if ((isAlive1!= null && isAlive1.equals("true"))||(isAlive2!= null && isAlive2.equals("true"))){
                        MailHelper.sendMail("kirin.li@dianping.com", context, "Taurus-Agent主机心跳异常告警服务");
                        MailHelper.sendWeChat("kirin.li",context, "Taurus-Agent主机心跳异常告警服务");
                        
                        reportAlarmToOps(host, 
                    					"Taurus-Agent主机心跳异常告警服务", 
                    					context, 
                    					domain + "/hosts?hostName=" + host, 
                    					"dpop@dianping.com",
                    					reportToOps);
                    }else
                    {
                        MailHelper.sendWeChat("kirin.li",exceptContext, "Taurus-Agent主机失联系告警服务");
                        String toMails = ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress()).getProperty("taurus.agent.down.mail.to");
                        String [] toLists = toMails.split(",");
                        for (String to:toLists){
                            MailHelper.sendMail(to, exceptContext, "Taurus-Agent主机失联系告警服务");
                        }

                        reportAlarmToOps(host, 
            					"Taurus-Agent主机失联系告警服务", 
            					exceptContext, 
            					domain + "/hosts?hostName=" + host, 
            					"monitor@dianping.com",
            					reportToOps);
                    }

                } catch (Exception e) {
                    Cat.logError(e);
                }
            }
        }
        
        // 检测其他agent:isOnline = 1 and isConnected = 1
        JSONObject jsonObj = JSONObject.fromObject(onlineHostsJsonStr);
        Object obj = jsonObj.get("hosts");
        
        if(StringUtil.isNotBlank(obj.toString())){
        	JSONArray jsonArr = jsonObj.getJSONArray("hosts");
			Object[] hostListsObj = jsonArr.toArray();
			
            for (Object hostObj: hostListsObj){
            	String host = hostObj.toString();
                String exceptContext = "您好，taurus-agent的job主机 ["
                        + host
                        + "] 服务已经挂掉请在【"+domain+"/host_center】核实并重启该Job机器的TOMCAT" +
                        "监控连接如下："+domain+"/hosts?hostName="
                        + host
                        +"，谢谢~";

                try {

                    String url1= "http://"+host+":8080/agentrest.do?action=isnew";
                    String url2 = "http://"+host+":8088/agentrest.do?action=isnew";
                    String isAlive1 = get_data(url1);
                    String isAlive2 = get_data(url2);

                    if(isAlive1 == null && isAlive2 == null) {//agent服务器无响应
                        MailHelper.sendWeChat("kirin.li",exceptContext, "Taurus-Agent主机失联系告警服务");
                        String toMails = ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress()).getProperty("taurus.agent.down.mail.to");
                        String [] toLists = toMails.split(",");
                        for (String to:toLists){
                            MailHelper.sendMail(to, exceptContext, "Taurus-Agent主机失联系告警服务");
                        }
                        
                        reportAlarmToOps(host, 
            					"Taurus-Agent主机失联系告警服务", 
            					exceptContext, 
            					domain + "/hosts?hostName=" + host, 
            					"monitor@dianping.com",
            					reportToOps);
                    }

                } catch (Exception e) {
                    Cat.logError(e);
                }
            }
        }
        
    }
    
    /**
     * 运维告警接口
     * @param domain
     * @param title
     * @param content
     * @param url
     * @param reportUrl 运维告警的post接口
     */
    public void reportAlarmToOps(String domain, String title, String content, String url, String receiver, String reportUrl) {
    	
    	// 给运维报警
        Map<String, String> header = new HashMap<String, String>();
        Map<String, String> body = new HashMap<String, String>();
        
        body.put("typeObject", "Taurus");
        body.put("typeItem", "Service");
        body.put("typeAttribute", "Status");
        body.put("source", "taurus");
        
        //此处动态修改
        body.put("domain", domain);
        body.put("title", title);
        body.put("content",content);
        body.put("url", url);
        
        body.put("receiver", receiver);
			
        HttpPoster.postWithoutException(reportUrl, header, body);
    }
    
    public static String get_data(String url) {
        try {
            URL httpUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) httpUrl.openConnection();
            conn.setDoOutput(true);
            conn.setConnectTimeout(1000);
            conn.connect();

            //返回
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String result = reader.readLine();
            return result.trim();
        } catch (Exception e) {

            return null;
        }
    }
}
