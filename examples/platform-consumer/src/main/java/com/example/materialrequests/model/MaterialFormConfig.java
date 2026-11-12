package com.example.materialrequests.model;

@org.springframework.stereotype.Component
@org.ipro.metadata.annotation.FormConfigurationFor(com.example.materialrequests.model.Material.class)
@org.ipro.metadata.annotation.ListColumns({"code", "name"})
@org.ipro.metadata.annotation.SelectionColumns({"code", "name"})
public class MaterialFormConfig {}
