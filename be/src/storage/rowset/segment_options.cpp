// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#include "segment_options.h"

namespace starrocks::vectorized {

Status SegmentReadOptions::convert_to(SegmentReadOptions* dst, const std::vector<FieldType>& new_types,
                                      ObjectPool* obj_pool) const {
    // ranges
    int num_ranges = ranges.size();
    dst->ranges.resize(num_ranges);
    for (int i = 0; i < num_ranges; ++i) {
        ranges[i].convert_to(&dst->ranges[i], new_types);
    }

    // predicates
    for (auto& pair : predicates) {
        auto cid = pair.first;
        int num_preds = pair.second.size();
        std::vector<const ColumnPredicate*> new_preds(num_preds, nullptr);
        for (int i = 0; i < num_preds; ++i) {
            RETURN_IF_ERROR(pair.second[i]->convert_to(&new_preds[i], get_type_info(new_types[cid]), obj_pool));
        }
        dst->predicates.emplace(pair.first, std::move(new_preds));
    }

    // delete predicates
    RETURN_IF_ERROR(delete_predicates.convert_to(&dst->delete_predicates, new_types, obj_pool));

    dst->block_mgr = block_mgr;
    dst->stats = stats;
    dst->use_page_cache = use_page_cache;
    dst->profile = profile;
    dst->global_dictmaps = global_dictmaps;
    return Status::OK();
}

std::string SegmentReadOptions::debug_string() const {
    std::stringstream ss;
    ss << "ranges=[";
    for (int i = 0; i < ranges.size(); ++i) {
        if (i != 0) {
            ss << ", ";
        }
        ss << ranges[i].debug_string();
    }
    ss << "],predicates=[";
    int i = 0;
    for (auto& pair : predicates) {
        if (i++ != 0) {
            ss << ",";
        }
        ss << "{id=" << pair.first << ",pred=[";
        for (int j = 0; j < pair.second.size(); ++j) {
            if (j != 0) {
                ss << ",";
            }
            ss << pair.second[j]->debug_string();
        }
        ss << "]}";
    }
    ss << "],delete_predicates={";
    ss << "},tablet_schema={";
    ss << "},use_page_cache=" << use_page_cache;
    return ss.str();
}

} // namespace starrocks::vectorized
