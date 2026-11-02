/** Customer capabilities, separate from organizer identity and commercial orders. */
@org.springframework.modulith.ApplicationModule(allowedDependencies={"ordering::api","identity::api","notification::api","directsales::api","shared::api"})
package sn.groupia.customeraccess;
