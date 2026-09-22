package sn.groupia.payment.infrastructure.provider.bictorys;
/** Never carries an HTTP response body, headers, customer details or a nested client exception. */
final class BictorysException extends RuntimeException {
    BictorysException(String code){super(code);}
}
