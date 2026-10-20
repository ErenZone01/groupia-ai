/** The media business module. Public contracts belong exclusively to its api packages. */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"catalog::api", "organization::api", "shared::api"})
package sn.groupia.media;
