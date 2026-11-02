/** Direct fixed-price storefront, independent from grouped import campaigns. */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "catalog::api",
            "media::api",
            "organization::api",
            "payment::api",
            "subscription::api",
            "shared::api"
        })
package sn.groupia.directsales;
