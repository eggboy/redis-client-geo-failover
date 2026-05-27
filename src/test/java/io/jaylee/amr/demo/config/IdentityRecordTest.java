package io.jaylee.amr.demo.config;

import io.jaylee.amr.demo.config.AmrRedisProperties.Identity;
import io.jaylee.amr.demo.config.AmrRedisProperties.Identity.IdentityType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityRecordTest {

	@Test
	void systemAcceptsBlankId() {
		assertThat(new Identity(IdentityType.SYSTEM, null).type()).isEqualTo(IdentityType.SYSTEM);
		assertThat(new Identity(IdentityType.SYSTEM, "").type()).isEqualTo(IdentityType.SYSTEM);
		assertThat(new Identity(IdentityType.SYSTEM, "   ").type()).isEqualTo(IdentityType.SYSTEM);
	}

	@Test
	void systemRejectsNonBlankId() {
		assertThatThrownBy(() -> new Identity(IdentityType.SYSTEM, "00000000-0000-0000-0000-000000000000"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must be blank when type=SYSTEM");
	}

	@Test
	void userAssignedRequiresId() {
		assertThatThrownBy(() -> new Identity(IdentityType.USER_ASSIGNED_CLIENT_ID, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("is required when type=USER_ASSIGNED_CLIENT_ID");
		assertThatThrownBy(() -> new Identity(IdentityType.USER_ASSIGNED_OBJECT_ID, ""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("is required when type=USER_ASSIGNED_OBJECT_ID");
		assertThatThrownBy(() -> new Identity(IdentityType.USER_ASSIGNED_RESOURCE_ID, "   "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("is required when type=USER_ASSIGNED_RESOURCE_ID");
	}

	@Test
	void userAssignedAcceptsId() {
		String cid = "11111111-2222-3333-4444-555555555555";
		String oid = "66666666-7777-8888-9999-aaaaaaaaaaaa";
		String rid = "/subscriptions/sub/resourceGroups/rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/my-id";
		assertThat(new Identity(IdentityType.USER_ASSIGNED_CLIENT_ID, cid).id()).isEqualTo(cid);
		assertThat(new Identity(IdentityType.USER_ASSIGNED_OBJECT_ID, oid).id()).isEqualTo(oid);
		assertThat(new Identity(IdentityType.USER_ASSIGNED_RESOURCE_ID, rid).id()).isEqualTo(rid);
	}

	@Test
	void typeIsRequired() {
		assertThatThrownBy(() -> new Identity(null, "anything")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("type must be set");
	}

}
