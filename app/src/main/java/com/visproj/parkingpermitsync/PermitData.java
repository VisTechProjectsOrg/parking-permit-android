package com.visproj.parkingpermitsync;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

public class PermitData {
    @SerializedName("permitNumber")
    public String permitNumber = "";

    @SerializedName("plateNumber")
    public String plateNumber = "";

    @SerializedName("vehicleName")
    public String vehicleName = "";

    @SerializedName("validFrom")
    public String validFrom = "";

    @SerializedName("validTo")
    public String validTo = "";

    @SerializedName("barcodeValue")
    public String barcodeValue = "";

    @SerializedName("barcodeLabel")
    public String barcodeLabel = "";

    @SerializedName("amountPaid")
    public String price = "";

    @SerializedName("displayFlipped")
    public boolean displayFlipped = false;

    public PermitData() {}

    public PermitData(String permitNumber, String plateNumber, String validFrom,
                      String validTo, String barcodeValue, String barcodeLabel) {
        this.permitNumber = permitNumber;
        this.plateNumber = plateNumber;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.barcodeValue = barcodeValue;
        this.barcodeLabel = barcodeLabel;
    }

    public boolean isValid() {
        return permitNumber != null && !permitNumber.isEmpty();
    }

    // Check all required fields for BLE transfer
    public boolean isComplete() {
        return permitNumber != null && !permitNumber.isEmpty()
            && plateNumber != null && !plateNumber.isEmpty()
            && validFrom != null && !validFrom.isEmpty()
            && validTo != null && !validTo.isEmpty()
            && barcodeValue != null && !barcodeValue.isEmpty()
            && barcodeLabel != null && !barcodeLabel.isEmpty();
    }

    public String toJson() {
        return new Gson().toJson(this);
    }
}
