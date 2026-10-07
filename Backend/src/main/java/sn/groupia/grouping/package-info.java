/** Campaign and offer contracts are exposed only through api. */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"organization::api", "catalog::api", "shipping::api", "subscription::api", "shared::api"})
package sn.groupia.grouping;
