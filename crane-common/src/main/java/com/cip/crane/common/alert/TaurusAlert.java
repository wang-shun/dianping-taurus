package com.cip.crane.common.alert;

import com.cip.crane.common.AttemptStatus;
import com.cip.crane.common.alert.healthcheck.HealthChecker;
import com.cip.crane.common.lion.ConfigHolder;
import com.cip.crane.common.lion.LionKeys;
import com.cip.crane.common.utils.EnvUtils;
import com.cip.crane.common.utils.SleepUtils;
import com.cip.crane.generated.mapper.*;
import com.cip.crane.generated.module.*;
import com.dianping.cat.Cat;
import com.dianping.lion.EnvZooKeeperConfig;
import com.dianping.lion.client.ConfigCache;
import com.dianping.lion.client.LionException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.mail.MessagingException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

;

/**
 * TaurusAlert
 * 
 * @author damon.zhu
 */
public class TaurusAlert {

	private static final Log LOG = LogFactory.getLog(TaurusAlert.class);

	private static final int ALERT_INTERVAL = 5 * 1000;

	private static final int META_INTERVAL = 60 * 1000;

	private List<AlertRule> commonRules;

	private Map<String, AlertRule> ruleMap;

	@Autowired
	private AlertRuleMapper rulesMapper;

	@Autowired
	private TaskAttemptMapper taskAttemptMapper;

	@Autowired
	private TaskMapper taskMapper;

	@Autowired
	private UserGroupMappingMapper userGroupMappingMapper;

	@Autowired
	private UserMapper userMapper;

	@Autowired
	private HealthChecker healthChecker;

	private Map<Integer, User> userMap;
	
	private final AtomicBoolean isInterrupt = new AtomicBoolean(false);
	
	private volatile boolean metaDataThreadRestFlag = false;
	
	private volatile boolean alertThreadRestFlag = false;

	public boolean isMetaDataThreadRestFlag() {
		return metaDataThreadRestFlag;
	}

	public boolean isAlertThreadRestFlag() {
		return alertThreadRestFlag;
	}

	public void load() {
		LOG.info("load alert....");
		
		Map<String, AlertRule> ruleMap = new ConcurrentHashMap<String, AlertRule>();
		List<AlertRule> commonRules = new ArrayList<AlertRule>();
		Map<Integer, User> userMap = new ConcurrentHashMap<Integer, User>();

		// load alert rules
		AlertRuleExample ruleExample = new AlertRuleExample();
		ruleExample.or();

		List<AlertRule> rules = rulesMapper.selectByExample(ruleExample);
		for (AlertRule ar : rules) {
			if (ar.getJobid().equals("*")) {
				commonRules.add(ar);
			} else {
				ruleMap.put(ar.getJobid(), ar);
			}
		}

		// load user
		UserExample userExample = new UserExample();
		userExample.or();

		List<User> users = userMapper.selectByExample(userExample);
		for (User user : users) {
			userMap.put(user.getId(), user);
		}

		// switch
		this.ruleMap = ruleMap;
		this.commonRules = commonRules;
		this.userMap = userMap;
	}

	public void start(int interval) {
		Thread updated = new Thread(new MetaDataUpdatedThread());
		updated.setName("MetaDataThread");
		updated.setDaemon(true);
		updated.start();

		Date now = new Date();
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(now);
		calendar.set(Calendar.MILLISECOND, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.add(Calendar.MINUTE, interval);

		Thread alert = new Thread(new AlertThread(calendar.getTime()));
		alert.setName("AlertThread");
		alert.setDaemon(true);
		alert.start();
	}

	public class AlertThread implements Runnable {
		private Date m_lastNotifyTime;

		public AlertThread(Date now) {
			m_lastNotifyTime = now;
		}

		private void handle(TaskAttempt attempt) {
			for (AlertRule commonRule : commonRules) {
				ruleHandler(attempt, commonRule);
			}

			AlertRule rule = ruleMap.get(attempt.getTaskid());

			if (rule != null) {
				ruleHandler(attempt, rule);
			}
		}

		private void ruleHandler(TaskAttempt attempt, AlertRule rule) {
			Set<Integer> ids = new HashSet<Integer>();
			String[] whens = StringUtils.isBlank(rule.getConditions()) ? null : rule.getConditions().split(";");
			String[] userId = StringUtils.isBlank(rule.getUserid()) ? null : rule.getUserid().split(";");
			String[] groupId = StringUtils.isBlank(rule.getGroupid()) ? null : rule.getGroupid().split(";");

			if (whens == null) {
				return;
			}

			for (String when : whens) {
				if (when.equalsIgnoreCase(AttemptStatus.getInstanceRunState(attempt.getStatus()))) {
					LOG.info("Condition matched : " + when);
					if (userId != null) {
						for (String id : userId) {
							ids.add(Integer.parseInt(id));
						}
					}

					if (groupId != null) {
						for (String id : groupId) {
							UserGroupMappingExample ugm_example = new UserGroupMappingExample();
							ugm_example.or().andGroupidEqualTo(Integer.parseInt(id));
							List<UserGroupMapping> userGroupMappings = userGroupMappingMapper.selectByExample(ugm_example);
							for (UserGroupMapping userGroupMapping : userGroupMappings) {
								ids.add(userGroupMapping.getUserid());
							}
						}
					}
				}
			}

			// Send alert
			for (Integer id : ids) {
				User user = userMap.get(id);

				if (user != null) {
					if (rule.getHasmail() && StringUtils.isNotBlank(user.getMail())) {
						sendMail(user.getName(), user.getMail(), attempt);

					}

					if (rule.getHassms() /*&& StringUtils.isNotBlank(user.getTel())*/) {
		                sendWeChat(user.getName(), attempt);
						//sendSMS(user.getTel(), attempt);
					}
					
					if(rule.getHasdaxiang()){
						DaXiangHelper.sendDaXiang(user.getMail(), contentBuild(attempt));
					}

				} else {
					Cat.logError("Cannot find user id : " + id, null);
				}
			}
		}

		@Override
		public void run() {
			while (true) {
				
				while(isInterrupt.get()) {
					alertThreadRestFlag = true;
					SleepUtils.sleep(5000);
					m_lastNotifyTime = new Date();
				}
				alertThreadRestFlag = false;

				try {
					Date now = new Date();
					TaskAttemptExample example = new TaskAttemptExample();
					example.or().andEndtimeGreaterThanOrEqualTo(m_lastNotifyTime).andEndtimeLessThan(now);
					List<TaskAttempt> attempts = taskAttemptMapper.selectByExample(example);
					m_lastNotifyTime = now;
					if (attempts != null && attempts.size() == 0) {
						continue;
					}
					for (TaskAttempt at : attempts) {
						handle(at);
					}

					if(!healthChecker.isHealthy()){
						WeChatHelper.sendWeChat(ConfigHolder.get(LionKeys.ADMIN_USER), EnvUtils.getEnv() + " zk上没有注册任何Taurus服务器", ConfigHolder.get(LionKeys.ADMIN_WECHAT_AGENTID));
					}

					Thread.sleep(ALERT_INTERVAL);
				} catch (Throwable e) {
					LOG.error(e, e);
				}

			}
		}

		private void sendMail(String to, String content) throws MessagingException {
			MailInfo mail = new MailInfo();
			mail.setTo(to);
			mail.setContent(content);
			mail.setFormat("text/html");
			mail.setSubject("Taurus告警服务");
			MailHelper.sendMail(mail);
		}

		private void sendMail(String userName,String mailTo, TaskAttempt attempt) {
			Cat.logEvent("Alert.Email", mailTo);
			LOG.info("Send mail to " + mailTo);
			String sbMailContent = contentBuild(attempt);

			try {
				sendMail(mailTo, sbMailContent.toString());
                /*Cat.logEvent("Alert.WeChat",userName );
                sendWeChat(userName,attempt);*/

			} catch (Exception e) {
				LOG.error("fail to send mail to " + mailTo, e);
				Cat.logError(e);
			}
		}

		private void sendSMS(String tel, TaskAttempt attempt) {
			Cat.logEvent("Alert.SMS", tel);
			LOG.info("Send SMS to " + tel);
			Task task = taskMapper.selectByPrimaryKey(attempt.getTaskid());
			StringBuilder sbMailContent = new StringBuilder();

			sbMailContent.append("任务名： " + task.getName() + "</br>");
			sbMailContent.append("任务状态： " + AttemptStatus.getInstanceRunState(attempt.getStatus()) + "</br>");

			try {
				Map<String, String> messageContent = new HashMap<String, String>();
				messageContent.put("body", sbMailContent.toString());

				// smsService.send(801, tel, messageContent);
			} catch (Exception e) {
				LOG.error("fail to send sms to " + tel, e);
				Cat.logError(e);
			}
		}

        private void sendWeChat(String user,TaskAttempt attempt) {
            Cat.logEvent("Alert.WeChat", user);
            LOG.info("Send WeChat to " + user);
            Task task = taskMapper.selectByPrimaryKey(attempt.getTaskid());

            try {
                WeChatHelper.sendWeChat(task.getCreator(), contentBuild(attempt), "12");
            } catch (Exception e) {
                LOG.error("fail to send WeChat to " + user, e);
                Cat.logError(e);
            }
        }
		
		private String contentBuild(TaskAttempt attempt){

			Task task = taskMapper.selectByPrimaryKey(attempt.getTaskid());

			StringBuilder sbContent = new StringBuilder();

			String domain;
			try {
				domain = ConfigCache.getInstance(EnvZooKeeperConfig.getZKAddress()).getProperty("taurus.web.serverName");
			} catch (LionException e) {
				domain="http://taurus.dp";
				e.printStackTrace();
			}
			sbContent.append("※ Taurus 任务执行状态告警服务 ※");
			sbContent.append("\n");
			sbContent.append("任务名:" + task.getName());
			sbContent.append("\n");
			sbContent.append("任务状态: " + AttemptStatus.getInstanceRunState(attempt.getStatus()));
			sbContent.append("\n");
			sbContent.append("日志查看:" + domain + "/viewlog?id=" + attempt.getAttemptid());
			sbContent.append("\n");
			sbContent.append("※ 美团点评架构组 ※");
			
			return sbContent.toString();
		}
	}

	public class MetaDataUpdatedThread implements Runnable {
		@Override
		public void run() {
			
			while (true) {
				
				while(isInterrupt.get()) {
					metaDataThreadRestFlag = true;
					SleepUtils.sleep(5000);
				}
				metaDataThreadRestFlag = false;
				
				try {
					load();
					Thread.sleep(META_INTERVAL);
				} catch (Throwable e) {
					Cat.logError(e);
					LOG.error(e, e);
				}
				
			}
		}


	}
	
	public void isInterrupt(boolean interrupt) {
		boolean current = isInterrupt.get();
		isInterrupt.compareAndSet(current, interrupt);
	}
	
    public  void startAlert(){
        TaurusAlert alert = new TaurusAlert();

        alert.start(-1);
    }

}
