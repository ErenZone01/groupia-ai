/**
 * The ordering business module. Public contracts belong exclusively to its api packages.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"organization::api", "grouping::api", "shipping::api", "shared::api"})
package sn.groupia.ordering;
