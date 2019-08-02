package com.delta.mes.gateway.entity;

import com.delta.mes.annotation.DataBaseColumnSetting;

public class URL {

	@DataBaseColumnSetting(columnName = "APPLICATION_NAME")
	private String applicationName;

	@DataBaseColumnSetting(columnName = "API_URL")
	private String url;

	public String getApplicationName() {
		return applicationName;
	}

	public void setApplicationName(String applicationName) {
		this.applicationName = applicationName;
	}

	public String getUrl() {

		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	@Override
	public String toString() {
		return "URL [applicationName=" + applicationName + ", url=" + url + "]";
	}

}
