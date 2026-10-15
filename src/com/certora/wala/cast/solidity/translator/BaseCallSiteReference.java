/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://eclipse.org.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: {name license(s), version(s), and
 * exceptions or additional permissions here}.
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.certora.wala.cast.solidity.translator;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.shrike.shrikeBT.IInvokeInstruction;
import com.ibm.wala.types.MethodReference;

/**
 * An explicit base-contract call {@code Base.f(...)}. Solidity binds it statically to the named
 * contract's {@code f}, with no virtual dispatch, but the body still runs on the calling
 * contract. It is a receiver-carrying (special) call, as a super call is; the call graph builder
 * tells the two apart by this class and binds it to the declared function's own body.
 */
public final class BaseCallSiteReference extends CallSiteReference {

	public BaseCallSiteReference(int programCounter, MethodReference declaredTarget) {
		super(programCounter, declaredTarget);
	}

	@Override
	public IInvokeInstruction.IDispatch getInvocationCode() {
		return IInvokeInstruction.Dispatch.SPECIAL;
	}
}
