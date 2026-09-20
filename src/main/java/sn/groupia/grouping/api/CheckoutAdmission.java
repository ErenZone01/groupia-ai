package sn.groupia.grouping.api;

import java.util.*;
import sn.groupia.grouping.api.dto.*;

/** Requires an existing write transaction; its campaign lock lives until that transaction commits. */
public interface CheckoutAdmission {
    CheckoutCampaign lockCampaign(UUID publicIdentifier);
    CheckoutQuote quote(CheckoutCampaign campaign, List<CheckoutSelection> selections);
}
