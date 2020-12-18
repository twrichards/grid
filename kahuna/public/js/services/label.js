import angular from 'angular';
import { trackAll } from '../util/batch-tracking';

var labelService = angular.module('kahuna.services.label', []);

labelService.factory('labelService',
                     ['$rootScope', '$q', 'apiPoll', 'imageAccessor',
                       // eslint-disable-next-line no-unused-vars
                       function ($rootScope, $q, apiPoll, imageAccessor) {


    function readLabelName(label) {
        return label.data;
    }

    function readLabelsName(labels) {
        return labels.map(readLabelName);
    }

    function labelsEquals(labelsA, labelsB) {
        return angular.equals(
            readLabelsName(labelsA).sort(),
            readLabelsName(labelsB).sort()
        );
    }
                        function untilLabelsEqual(image, expectedLabels) {
        return image.get().then(apiImage => {
            const apiLabels = imageAccessor.readLabels(apiImage);
            if (labelsEquals(apiLabels, expectedLabels)) {
                return apiImage;
            } else {
                return $q.reject();
            }
        });
    }

    function remove (image, label) {
      return batchRemove([image], label);
    }

    function add (image, labels) {
      return batchAdd([image], labels);
    }



                        function batchAdd(images, labels) {
                          const sendAdd = (image) => {
                            labels = labels.filter(label => label && label.trim().length > 0);

                            return image.data.userMetadata.data.labels
                              .post({ data: labels });
                          };
                          const checkAdd = async (image, result) => {
                            const r = await apiPoll(() => untilLabelsEqual(image, result.data));
                            console.log(r, "AAA");
                            return r;
                          };

                          return trackAll($rootScope, "label", images, [sendAdd, checkAdd], 'image-updated');
    }

                         function batchRemove(images, label) {

                           const imagesWithLabel = images.map(image => {
                             const labels = imageAccessor.readLabels(image);
                             const l = labels.find(({ data }) => data === label);
                             return { image, label: l};
                           }).filter(({label})=>label !== undefined);
                           if (imagesWithLabel.length === 0) {
                             return Promise.resolve();
                           }

                           return trackAll($rootScope, "label", imagesWithLabel, [
                             ({ label }) => label.delete(),
                             ({ image }, result) => apiPoll(() => untilLabelsEqual(image, result.data))
                           ],
                             'image-updated');
    }

    return {
        add,
        remove,
        batchAdd,
        batchRemove
    };
}]);

export default labelService;
