#pragma once

#include <windows.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

#define RETURN_IF_FAILED(expression)            \
    do {                                        \
        const HRESULT result_ = (expression);   \
        if (FAILED(result_)) return result_;    \
    } while (false)
