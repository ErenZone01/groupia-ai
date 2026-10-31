package sn.groupia.ordering.api.dto;

/**
 * Indication de localisation pour organiser une remise ou une éventuelle livraison. Ne crée aucune
 * promesse de livraison à domicile ni de tarif de dernier kilomètre.
 */
public record DeliveryAddress(
        String country,
        String region,
        String city,
        String commune,
        String neighborhood,
        String details) {}
