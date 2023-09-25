package com.simplefanc.voj.judger.judge.remote.provider.mxt;

import com.simplefanc.voj.common.constants.RemoteOj;
import com.simplefanc.voj.judger.judge.remote.pojo.RemoteOjInfo;
import org.apache.http.HttpHost;

public class MXTInfo {

    public static final RemoteOjInfo INFO = new RemoteOjInfo(RemoteOj.MXT,
            new HttpHost("mxt.cn", 443, "https"));

}
