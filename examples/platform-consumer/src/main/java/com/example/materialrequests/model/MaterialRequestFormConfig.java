package com.example.materialrequests.model;

@org.springframework.stereotype.Component
@org.ipro.metadata.annotation.FormConfigurationFor(com.example.materialrequests.model.MaterialRequest.class)
@org.ipro.metadata.annotation.ListColumns({"number", "date", "departmentId", "status"})
@org.ipro.metadata.annotation.SelectionColumns({"number", "date", "departmentId", "status"})
public class MaterialRequestFormConfig {}
