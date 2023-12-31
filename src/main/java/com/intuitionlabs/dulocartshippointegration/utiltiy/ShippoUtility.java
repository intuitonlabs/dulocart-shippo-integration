package com.intuitionlabs.dulocartshippointegration.utiltiy;

import com.intuitionlabs.dulocartshippointegration.exceptions.InvalidAddressException;
import com.intuitionlabs.dulocartshippointegration.exceptions.TransactionFailException;
import com.intuitionlabs.dulocartshippointegration.model.ParcelInfo;
import com.intuitionlabs.dulocartshippointegration.model.ShippoAddress;
import com.intuitionlabs.dulocartshippointegration.model.ShippoTrackingInformation;
import com.shippo.Shippo;
import com.shippo.exception.APIConnectionException;
import com.shippo.exception.APIException;
import com.shippo.exception.AuthenticationException;
import com.shippo.exception.InvalidRequestException;
import com.shippo.model.*;

import java.util.*;

public class ShippoUtility {

    private static final String API_KEY = System.getenv("SHIPPO_API_KEY");
    private static final String API_VERSION = "2018-02-08";
    private static final String SUCCESS_SHIPMENT_RESPONSE_TEXT = "SUCCESS";

    public static void setApi() {
        Shippo.setApiKey(API_KEY);
        Shippo.setApiVersion(API_VERSION);
    }

    public static Rate getShippingRate(ShippoAddress fromAddress, ShippoAddress toAddress, List<ParcelInfo> parcelInfo, Currency currency) throws APIConnectionException, APIException, AuthenticationException, InvalidRequestException {
        setApi();

        Address toAddressShippo = createToAddress(toAddress);
        throwInvalidAddressException(toAddressShippo);
        Address fromAddressShippo = createFromAddress(fromAddress);
        throwInvalidAddressException(fromAddressShippo);

        List<Map<String, Object>> parcel = createParcel(parcelInfo);

        Shipment shipment = createShipment(fromAddressShippo, toAddressShippo, parcel);
        RateCollection rateCollection = createRateCollection(shipment, currency);
        Collections.sort(rateCollection.getData(), Comparator.comparingDouble((rate) -> Double.parseDouble((String)rate.getAmountLocal())));

        return rateCollection.getData().get(0);
    }


    public static ShippoTrackingInformation initShipping(ShippoAddress fromAddress, ShippoAddress toAddress, List<ParcelInfo> parcelInfo, Rate rate) throws APIConnectionException, APIException, AuthenticationException, InvalidRequestException {
        setApi();

        Address toAddressShippo = createToAddress(toAddress);
        Address fromAddressShippo = createFromAddress(fromAddress);

        List<Map<String, Object>> parcel = createParcel(parcelInfo);
        createShipment(fromAddressShippo, toAddressShippo, parcel);

        return createTransactionGetTrackingInfo(rate);
    }

    public static Address createToAddress(ShippoAddress address) throws APIConnectionException, APIException, AuthenticationException, InvalidRequestException {
        Map<String, Object> fromAddressMap = new HashMap<String, Object>();
        fromAddressMap.put("name", address.getName());
        fromAddressMap.put("street1", address.getStreet1());
        fromAddressMap.put("street2", address.getStreet2());
        fromAddressMap.put("city", address.getCity());
        fromAddressMap.put("state", address.getState());
        fromAddressMap.put("zip", address.getZipCode());
        fromAddressMap.put("country", address.getCountry());
        fromAddressMap.put("email", address.getEmail());
        fromAddressMap.put("phone", address.getPhone());
        fromAddressMap.put("validate", true);

        return Address.create(fromAddressMap);
    }

    public static Address createFromAddress(ShippoAddress address) throws APIConnectionException, APIException, AuthenticationException, InvalidRequestException {
        Map<String, Object> fromAddressMap = new HashMap<String, Object>();
        fromAddressMap.put("name", address.getName());
        fromAddressMap.put("company", address.getCompany());
        fromAddressMap.put("street1", address.getStreet1());
        fromAddressMap.put("street2", address.getStreet2());
        fromAddressMap.put("city", address.getCity());
        fromAddressMap.put("state", address.getState());
        fromAddressMap.put("zip", address.getZipCode());
        fromAddressMap.put("country", address.getCountry());
        fromAddressMap.put("email", address.getEmail());
        fromAddressMap.put("phone", address.getPhone());
        fromAddressMap.put("validate", true);

        return Address.create(fromAddressMap);
    }

    /**
     * Create parcels form the parcelsInfo list
     * Handle cart with weight more than max parcel weight of the carrier parcels
     * @param parcelsInfo
     * @return
     */
    public static List<Map<String, Object>> createParcel(List<ParcelInfo> parcelsInfo) {
        List<Map<String, Object>> parcels = new ArrayList<>();

        for(ParcelInfo info : parcelsInfo) {
            Map<String, Object> parcelMap = new HashMap<>();

            parcelMap.put("length", info.getLength());
            parcelMap.put("width", info.getWidth());
            parcelMap.put("height", info.getHeight());
            parcelMap.put("distance_unit", info.getDistance_unit());
            parcelMap.put("weight", info.getWeight());
            parcelMap.put("mass_unit", info.getMass_unit());

            parcels.add(parcelMap);
        }

        return parcels;
    }

    public static Shipment createShipment(Address addressFrom, Address addressTo, List<Map<String, Object>> parcels) throws APIConnectionException, APIException, AuthenticationException, InvalidRequestException {
        Map<String, Object> shipmentMap = new HashMap<String, Object>();
        shipmentMap.put("address_to", addressTo);
        shipmentMap.put("address_from", addressFrom);
        shipmentMap.put("parcels", parcels);
        shipmentMap.put("async", false);

        return Shipment.create(shipmentMap);
    }

    public static RateCollection createRateCollection(Shipment shipment, Currency currency) throws APIConnectionException, APIException, AuthenticationException, InvalidRequestException {
        Map<String, Object> rateMap = new HashMap<String, Object>();
        rateMap.put("id", shipment.getObjectId());
        rateMap.put("currency_code", currency.getCurrencyCode());
        rateMap.put("async", false);

        return Shipment.getShippingRates(rateMap);
    }

    public static Rate getRate(Shipment shipment, String rateId) {
        Optional<Rate> foundRate = shipment.getRates().stream().filter(x -> x.getObjectId().equals(rateId)).findFirst();
        if(foundRate.isEmpty()) {
            throw new TransactionFailException(String.format("Shippo rate with id %s not found. Please try again as request another rate.", rateId));
        }

        return foundRate.get();
    }

    public static ShippoTrackingInformation createTransactionGetTrackingInfo(Rate rate) throws APIConnectionException, APIException, AuthenticationException, InvalidRequestException {
        Map<String, Object> transParams = new HashMap<>();
        transParams.put("rate", rate.getObjectId());
        transParams.put("async", false);
        Transaction transaction = Transaction.create(transParams);

        if (transaction.getStatus().equals(SUCCESS_SHIPMENT_RESPONSE_TEXT)) {
            String shippingLabel = transaction.getLabelUrl().toString();
            String trackingNumber = transaction.getTrackingNumber().toString();
            String trackingUrl = transaction.getTrackingUrlProvider().toString();

            return ShippoTrackingInformation.builder()
                    .trackingNumber(trackingNumber)
                    .carrier(rate.getProvider().toString())
                    .labelUrl(shippingLabel)
                    .trackingUrl(trackingUrl)
                    .build();

        } else {
            throw new TransactionFailException(String.format("The creating of transaction with the delivery company failed. Please try again. Info: %s", transaction.getMessages()));
        }
    }

    public static Exception throwInvalidAddressException(Address address) {
        if(!address.getValidationResults().getIsValid()) {
            throw new InvalidAddressException(String.format("The provided address is invalid. Please enter correct value. Info address zip %s", address.getZip()));
        } else {
            return null;
        }
    }

}
