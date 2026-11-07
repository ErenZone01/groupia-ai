package sn.groupia.subscription.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "groupia.subscription")
@Component
public class SubscriptionSettings {
    private boolean enabled = true;
    private boolean enforcementEnabled;
    private int trialMonths = 1;
    private String monthlyXof = "10000";
    private boolean bictorysEnabled;
    private String bictorysPlanId = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    public void setEnforcementEnabled(boolean enforcementEnabled) {
        this.enforcementEnabled = enforcementEnabled;
    }

    public int getTrialMonths() {
        return trialMonths;
    }

    public void setTrialMonths(int trialMonths) {
        this.trialMonths = trialMonths;
    }

    public String getMonthlyXof() {
        return monthlyXof;
    }

    public void setMonthlyXof(String monthlyXof) {
        this.monthlyXof = monthlyXof;
    }

    public boolean isBictorysEnabled() {
        return bictorysEnabled;
    }

    public void setBictorysEnabled(boolean bictorysEnabled) {
        this.bictorysEnabled = bictorysEnabled;
    }

    public String getBictorysPlanId() {
        return bictorysPlanId;
    }

    public void setBictorysPlanId(String bictorysPlanId) {
        this.bictorysPlanId = bictorysPlanId;
    }
}
