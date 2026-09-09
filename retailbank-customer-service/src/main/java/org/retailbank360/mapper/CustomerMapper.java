package org.retailbank360.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;
import org.retailbank360.dto.CustomerPatchRequest;
import org.retailbank360.entity.Customer;

/**
 * Field-by-field copy for partial customer updates.
 *
 * <p>{@code NullValuePropertyMappingStrategy.IGNORE} is the whole point: for each property, a
 * {@code null} on the request is skipped and the entity keeps its current value, so only the fields
 * the caller actually sent are touched. {@code unmappedTargetPolicy = IGNORE} silences warnings for
 * the entity-only fields ({@code id}, {@code status}, {@code kycStatus}, {@code version}, audit
 * columns) that the patch DTO has no counterpart for.</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CustomerMapper {

    /** Copies only the non-null fields of {@code request} onto {@code customer}. */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateFromPatch(CustomerPatchRequest request, @MappingTarget Customer customer);
}
