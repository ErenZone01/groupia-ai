/** Immutable supplier purchasing. Coordinates grouping and ordering through public APIs without cycles. */
@org.springframework.modulith.ApplicationModule(allowedDependencies={"grouping::api","ordering::api","organization::api","shared::api"})
package sn.groupia.procurement;
