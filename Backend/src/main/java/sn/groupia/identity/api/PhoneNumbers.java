package sn.groupia.identity.api;
/** Canonical E.164 using the existing validated phone policy (SN for national input). */
public interface PhoneNumbers { String normalize(String input); }
