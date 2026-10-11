package org.ip.navigation;

import org.ip.model.AccountingObjectCorrection;
import org.ip.model.AccountingObjectInstallation;
import org.ip.model.AccountingType;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.Branch;
import org.ip.model.GroupNom;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclatureAccountingObject;
import org.ip.model.NomenclaturePrice;
import org.ip.model.NomSklAttribute;
import org.ip.model.Oper;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.SklNomOpa;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ip.subsystem.Subsystems;
import org.ipro.navigation.NavigationDefaults;
import org.ipro.navigation.NavigationDefaultsContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stable navigation identities. Keep literals when renaming Java types or moving packages;
 * membership, titles and ordering continue to come from the existing subsystem metadata.
 * The UI projection consumes these declarations without changing source metadata.
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationNavigationConfiguration {
    @Bean
    NavigationDefaultsContributor applicationNavigationDefaults() {
        return (NavigationDefaults defaults) -> defaults
            .subsystem(Subsystems.Directories.class, "directories")
            .subsystem(Subsystems.Documents.class, "documents")
            .subsystem(Subsystems.ProductionDocuments.class, "production-documents")
            .subsystem(Subsystems.Production.class, "production")
            .list(Subsystems.Directories.class, AccountingType.class, "accounting-types-list")
            .list(Subsystems.Directories.class, AttributeType.class, "attribute-types-list")
            .list(Subsystems.Directories.class, AttributeValue.class, "attribute-values-list")
            .list(Subsystems.Directories.class, Branch.class, "branches-list")
            .list(Subsystems.Directories.class, GroupNom.class, "nomenclature-groups-list")
            .list(Subsystems.Directories.class, Nomenclature.class, "nomenclature-list")
            .list(Subsystems.Directories.class, NomenclaturePrice.class, "nomenclature-prices-list")
            .list(Subsystems.Directories.class, NomSklAttribute.class, "storage-attributes-list")
            .list(Subsystems.Directories.class, SklNomOpa.class, "storage-attribute-sets-list")
            .list(Subsystems.Directories.class, UnitOfMeasurement.class, "units-list")
            .list(Subsystems.Directories.class, Workshop.class, "workshops-list")
            .list(Subsystems.Production.class, Journal.class, "journals-list")
            .list(Subsystems.Production.class, Oper.class, "operations-list")
            .list(Subsystems.Production.class, PrdSpec.class, "specifications-list")
            .list(Subsystems.Production.class, ReceivingDocument.class, "receiving-documents-list")
            .list(Subsystems.Production.class, NomenclatureAccountingObject.class, "accounting-objects-list")
            .list(Subsystems.Production.class, AccountingObjectInstallation.class, "accounting-object-installations-list")
            .list(Subsystems.Production.class, AccountingObjectCorrection.class, "accounting-object-corrections-list");
    }
}
